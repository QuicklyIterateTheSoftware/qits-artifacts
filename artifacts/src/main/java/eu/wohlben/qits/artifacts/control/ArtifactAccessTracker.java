package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.persistence.ArtifactRecordRepository;
import eu.wohlben.qits.artifacts.persistence.DaemonBinaryRepository;
import eu.wohlben.qits.artifacts.persistence.DocsSiteRepository;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;

/** Repository-scoped, one-hour-coalesced access writes for cleanup's reachability roots. */
@ApplicationScoped
public class ArtifactAccessTracker {

  static final Duration WRITE_WINDOW = Duration.ofHours(1);

  @Inject ArtifactRecordRepository records;
  @Inject OciManifestRepository manifests;
  @Inject OciTagRepository tags;
  @Inject NpmVersionRepository npmVersions;
  @Inject MavenArtifactRepository mavenArtifacts;
  @Inject DaemonBinaryRepository daemonBinaries;
  @Inject DocsSiteRepository docsSites;

  @Transactional
  public void touchArtifact(String repository, String blobId, Instant now) {
    records.touchByRepositoryAndBlob(repository, blobId, cutoff(now), now);
  }

  @Transactional
  public void touchManifest(
      String repository, String imageName, String digest, String tag, Instant now) {
    manifests.touch(repository, imageName, digest, cutoff(now), now);
    if (tag != null) {
      tags.touch(repository, imageName, tag, cutoff(now), now);
    }
  }

  /**
   * One npm version, hosted or proxied — {@code npm_version} is one table for both types, so this is
   * one method and the tarball route stays one code path. The proxy's packument row is untouched:
   * its {@code fetched_at} answers "when was the document last revalidated", which is a different
   * question from "when were these bytes last wanted".
   */
  @Transactional
  public void touchNpmVersion(String repository, String packageName, String version, Instant now) {
    npmVersions.touch(repository, packageName, version, cutoff(now), now);
  }

  /** One deployed maven path. The derived documents and checksums are not this row's bytes. */
  @Transactional
  public void touchMavenArtifact(String repository, String path, Instant now) {
    mavenArtifacts.touch(repository, path, cutoff(now), now);
  }

  /**
   * One published daemon version, reached by the version-addressed route.
   *
   * <p>There is no digest-addressed twin here on purpose: that download is the {@code /v2} blob
   * route, which resolves an OCI repository and a globally deduplicated digest and therefore carries
   * no daemon identity — the same reason layer reads stay unattributed.
   */
  @Transactional
  public void touchDaemonBinary(String repository, String name, String version, Instant now) {
    daemonBinaries.touch(repository, name, version, cutoff(now), now);
  }

  /**
   * One published docs version, reached by any file in it.
   *
   * <p>There is no per-file twin and no {@code docs_file} column to write: the site is what ages out
   * and so the site is what records being wanted. It also makes the coalescing matter more than
   * anywhere else here — one page load is fifty requests against one row, and the one-hour window
   * turns that into a single update.
   */
  @Transactional
  public void touchDocsSite(String repository, String name, String version, Instant now) {
    docsSites.touch(repository, name, version, cutoff(now), now);
  }

  private static Instant cutoff(Instant now) {
    return now.minus(WRITE_WINDOW);
  }
}
