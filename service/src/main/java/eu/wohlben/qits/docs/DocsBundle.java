package eu.wohlben.qits.docs;

import eu.wohlben.qits.artifacts.control.BlobStore;
import eu.wohlben.qits.artifacts.control.DocsMediaTypes;
import eu.wohlben.qits.artifacts.control.DocsRegistryService.BundleFile;
import eu.wohlben.qits.artifacts.error.DocsException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Turns an uploaded {@code .tar.gz} into the list of staged blobs a publish inserts.
 *
 * <p><b>Nothing here is ever written to a path the archive chose.</b> That is the whole security
 * posture and it is structural rather than a check that could be forgotten: an entry's bytes go into
 * {@code BlobStore}, which addresses them by their own SHA-256 and files them under a path it
 * computes itself, and the entry's *name* survives only as a string in a database column. There is
 * no {@code resolve(entry.getName())} in this class because there is no directory being unpacked
 * into. A traversing entry name is still rejected — {@link #relativise} does it — but the rejection
 * is defence in depth over a design that has nowhere for the attack to land.
 *
 * <p>The remaining hostile inputs are about size rather than location, and a compressed archive
 * makes them cheap to send: a gzip bomb is a few kilobytes on the wire and a terabyte on disk. So
 * the caps are on the <b>uncompressed</b> total and the file count, checked as entries are read, not
 * on the request body — {@code Content-Length} bounds the wrong number entirely.
 *
 * <p>Streaming throughout: one entry at a time through an 8 KiB buffer inside {@code BlobStore},
 * never the archive in memory and never a whole file. {@code TarArchiveInputStream} reports
 * end-of-entry as {@code read() == -1}, which is exactly the contract {@code BlobStore.stage}
 * consumes, so each entry stages as though it were its own upload.
 *
 * <p><b>The archive arrives as a stream, not as a path.</b> It used to be a temp file in the blob
 * store's staging directory; the store keeps its staging in {@code blob_chunk} rows now, so what the
 * caller hands over is a read-back over those rows ({@code ScratchBlob.openRead}). Nothing here
 * cares which it is — the class only ever read the archive forward.
 */
final class DocsBundle {

  private DocsBundle() {}

  /**
   * Bounds the matcher's work and the row count on a hostile archive. Storybook's own output is 53
   * files; a site with more than this many is not a documentation bundle.
   */
  private static final int MAX_FILES = 20_000;

  /**
   * Reads {@code archive}, staging and promoting every regular file into {@code blobStore}.
   *
   * @param archive the gzipped tar, read forward exactly once. <b>This method closes it</b>, along
   *     with the decompression chain over it — a caller closing again is harmless.
   * @param maxTotalBytes the cap on the <b>uncompressed</b> bundle, and on any single file in it
   * @return one entry per file, in archive order
   * @throws DocsException 400 for a malformed or hostile archive, 413 past the caps
   */
  static List<BundleFile> stageAll(InputStream archive, BlobStore blobStore, long maxTotalBytes) {
    List<BundleFile> staged = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    long total = 0;

    try (InputStream in = archive;
        TarArchiveInputStream tar =
            new TarArchiveInputStream(new GZIPInputStream(new BufferedInputStream(in)))) {

      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        // Directories carry no bytes and a docs_file row for one would be a path that serves
        // nothing. Symlinks and hardlinks are skipped rather than followed: their target is a path
        // in a tree that is never materialised, so there is nothing for them to point at.
        if (entry.isDirectory() || entry.isSymbolicLink() || entry.isLink()) {
          continue;
        }
        if (!entry.isFile()) {
          continue;
        }

        String path = relativise(entry.getName());
        if (path == null) {
          continue;
        }
        if (!seen.add(path)) {
          throw new DocsException(400, "the bundle carries '" + path + "' more than once");
        }
        if (seen.size() > MAX_FILES) {
          throw new DocsException(
              400, "the bundle carries more than " + MAX_FILES + " files — that is not a docs site");
        }

        // The remaining budget, so the running total is what bounds the archive rather than each
        // file being allowed the whole cap. A bomb of a thousand just-under-the-cap files is the
        // case a per-file limit alone would miss.
        long remaining = maxTotalBytes - total;
        BlobStore.StagedBlob blob = blobStore.stage(tar, remaining);
        blobStore.promote(blob);
        total += blob.size();

        staged.add(
            new BundleFile(path, blob.sha256(), blob.size(), DocsMediaTypes.forPath(path)));
      }
    } catch (DocsException e) {
      throw e;
    } catch (IOException e) {
      // A truncated upload and a file that is not a gzipped tar arrive here identically, and both
      // are the publisher's problem rather than this service's.
      throw new DocsException(400, "the bundle is not a readable .tar.gz: " + e.getMessage());
    }
    return staged;
  }

  /**
   * Normalises a tar entry name to a bundle-relative path, or {@code null} for an entry that is not
   * part of the site.
   *
   * <p>{@code tar -czf … -C storybook-static .} — the spelling a pipeline reaches for — names every
   * entry {@code ./index.html}, so the leading {@code ./} is stripped rather than treated as a
   * directory called {@code .}. The bare {@code .} entry that same command emits for the root
   * becomes empty and is dropped.
   *
   * <p>An absolute name or one containing a {@code ..} segment is refused outright. As the class
   * javadoc says, there is no directory for such a name to escape into — but a stored path that
   * begins with {@code /} or climbs would go on to be concatenated into URLs by qits-docs and into
   * hrefs by a browser, and "it cannot hurt us here" is a poor reason to persist it.
   *
   * @throws DocsException 400 for a traversing or absolute name
   */
  private static String relativise(String raw) {
    if (raw == null) {
      return null;
    }
    // Windows-built archives, and a defensive normalisation before the segment check rather than
    // after it: a `..\` that becomes `../` afterwards would have been checked in the wrong form.
    String path = raw.replace('\\', '/');
    while (path.startsWith("./")) {
      path = path.substring(2);
    }
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    if (path.isEmpty() || path.equals(".")) {
      return null;
    }
    if (path.startsWith("/")) {
      throw new DocsException(400, "the bundle carries an absolute path: " + raw);
    }
    for (String segment : path.split("/")) {
      if (segment.equals("..")) {
        throw new DocsException(400, "the bundle carries a traversing path: " + raw);
      }
    }
    return path;
  }
}
