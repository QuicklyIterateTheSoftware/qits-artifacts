package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.persistence.ArtifactRecordRepository;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.NpmProxyPackumentRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which blobs the store's identities still reach, per repository type, beside what is on disk.
 *
 * <p><b>One census, two readers.</b> This is the computation {@code ArtifactExplorerService} used to
 * do inline to name the store's orphans; garbage collection needs exactly the same set, and a second
 * implementation of it would be a set the UI reports and a set the sweep protects, drifting silently
 * until a sweep deletes something the summary called live. So the explorer's summary and every GC
 * plan are built from this one class, and its byte-exactness is proved by the explorer's own tests:
 * {@code diskTotal = ociUnion + npmPublished + npmProxyTarballs + orphans}.
 *
 * <p>The type split is what makes per-type strategies safe. A blob dedupes globally, so "is this
 * blob garbage" is never a question one type can answer — but "which blobs does <em>my</em> type
 * still reach" is, and the reconciliation across all of them belongs to {@link BlobSweep}, not to
 * any strategy.
 *
 * <p>Liveness per type, and where it is read from:
 *
 * <ul>
 *   <li>{@code oci-images} — the manifest closure ({@link OciManifestFootprints}, which walks an
 *       index's children, so a child manifest of a live index is live). Sizes are the {@code size}
 *       fields inside the manifest documents, which is what the OCI union has always been counted
 *       from.
 *   <li>{@code npm-packages} / {@code npm-proxy} — {@code npm_version.tarball_blob_id}, sized from
 *       disk because there is no size column.
 *   <li>{@code ci-screenshots} / {@code ci-videos} — {@code artifact_record.blob_id}, sized from the
 *       row, the one place a size sits beside an id.
 * </ul>
 *
 * <p>Records are attributed to their repository's type rather than only to the two CI types. No
 * protocol repository can hold one — their profiles accept no media type, so {@code BlobService}
 * refuses the upload — and attributing by the row rather than by an assumption is what keeps this
 * honest if that ever changes.
 *
 * <p><b>What this census cannot see is not garbage — it is untouchable.</b> A blob on disk that no
 * identity row names appears in {@link Census#rowless()}, and no strategy may ever release one: the
 * store's largest such pool is three ELF binaries uploaded through the OCI blob-upload session, one
 * of which is the CI daemon every build downloads. Row-less blobs are reported and left alone; the
 * git host's DFS pack blobs are in that pool too until its GC (a later workstream) contributes them
 * as a live set of its own.
 */
@ApplicationScoped
public class LiveBlobCensus {

  @Inject ArtifactRepositoryRepository repositories;
  @Inject ArtifactRecordRepository records;
  @Inject OciManifestRepository manifests;
  @Inject NpmVersionRepository versions;
  @Inject NpmProxyPackumentRepository packuments;
  @Inject OciManifestFootprints footprints;
  @Inject BlobDiskIndex diskIndex;

  /**
   * One reading of the store: every blob file, and every blob each type still reaches.
   *
   * <p>A value object, so a caller can hold it across a plan without the store shifting under it —
   * which is also why a sweep must re-take it immediately before unlinking anything.
   *
   * @param takenAt when the reading was taken; a plan older than a push is a plan on stale facts
   * @param onDisk blob id (bare hex) to bytes on disk, for every file under the blob root
   * @param liveByType blob id to the size that type knows for it — declared manifest sizes for OCI,
   *     disk sizes for npm tarballs, row sizes for CI records
   * @param ociPerImageSumBytes the per-image unions added up, which double-counts what images share
   *     and is reported beside the union rather than instead of it
   * @param npmProxyPackumentBytes cached packument characters — H2 CLOBs, not files, so outside
   *     every disk figure here
   */
  public record Census(
      Instant takenAt,
      Map<String, Long> onDisk,
      Map<RepositoryType, Map<String, Long>> liveByType,
      long ociPerImageSumBytes,
      long npmProxyPackumentBytes) {

    public Census {
      onDisk = Map.copyOf(onDisk);
      Map<RepositoryType, Map<String, Long>> copy = new EnumMap<>(RepositoryType.class);
      liveByType.forEach((type, live) -> copy.put(type, Map.copyOf(live)));
      liveByType = Map.copyOf(copy);
    }

    /** Every blob this type still reaches, with the size that type knows for it. */
    public Map<String, Long> live(RepositoryType type) {
      return liveByType.getOrDefault(type, Map.of());
    }

    /** The type's union, summed once — never a sum over its identities. */
    public long liveBytes(RepositoryType type) {
      return OciManifestFootprints.sum(live(type));
    }

    /** Every blob any type reaches. The complement of {@link #rowless()} within the disk walk. */
    public Set<String> referenced() {
      Set<String> referenced = new HashSet<>();
      liveByType.values().forEach(live -> referenced.addAll(live.keySet()));
      return referenced;
    }

    /**
     * Blob files no identity row of any type names — the untouchable pool.
     *
     * <p>Untouchable is stronger than "not currently a candidate": a blob can only become sweepable
     * by <em>losing</em> its last identity row to a strategy's own deletion, so a blob that never
     * had one is out of reach of the whole mechanism by construction.
     */
    public Set<String> rowless() {
      Set<String> referenced = referenced();
      Set<String> rowless = new LinkedHashSet<>();
      onDisk.keySet().stream().sorted().filter(id -> !referenced.contains(id)).forEach(rowless::add);
      return rowless;
    }

    public long rowlessBytes() {
      return bytesOnDisk(rowless());
    }

    public long diskTotalBytes() {
      return OciManifestFootprints.sum(onDisk);
    }

    /** What unlinking these blobs would actually free. A blob with no file frees nothing. */
    public long bytesOnDisk(Collection<String> blobIds) {
      long total = 0;
      for (String blobId : blobIds) {
        total += onDisk.getOrDefault(blobId, 0L);
      }
      return total;
    }
  }

  /**
   * Takes a fresh reading: one disk walk (cached and write-invalidated in {@link BlobDiskIndex}) and
   * one pass over the manifest, version and record rows.
   */
  public Census take() {
    Map<String, Long> onDisk = diskIndex.sizes();
    Map<RepositoryType, Map<String, Long>> live = new EnumMap<>(RepositoryType.class);
    for (RepositoryType type : RepositoryType.values()) {
      live.put(type, new HashMap<>());
    }

    List<ArtifactRepository> all = repositories.listAll();
    long perImageSum = 0;
    for (ArtifactRepository repository : all) {
      if (repository.type == RepositoryType.OCI_IMAGES) {
        Map<String, Long> ociUnion = live.get(RepositoryType.OCI_IMAGES);
        for (String image : manifests.listImageNames(repository.name)) {
          Map<String, Long> perImage =
              footprints.union(manifests.listByImage(repository.name, image));
          perImageSum += OciManifestFootprints.sum(perImage);
          perImage.forEach(ociUnion::putIfAbsent);
        }
      }
      Map<String, Long> recordBlobs = live.get(repository.type);
      for (Object[] blob : records.listDistinctBlobs(repository.name)) {
        recordBlobs.putIfAbsent((String) blob[0], (Long) blob[1]);
      }
    }

    List<String> hosted = namesOfType(all, RepositoryType.NPM_PACKAGES);
    List<String> proxied = namesOfType(all, RepositoryType.NPM_PROXY);
    tarballs(hosted, onDisk, live.get(RepositoryType.NPM_PACKAGES));
    tarballs(proxied, onDisk, live.get(RepositoryType.NPM_PROXY));

    return new Census(
        Instant.now(), onDisk, live, perImageSum, packuments.totalDocLength(proxied));
  }

  /** The npm half: distinct tarballs, sized from disk because {@code npm_version} has no size. */
  private void tarballs(
      List<String> repositoryNames, Map<String, Long> onDisk, Map<String, Long> into) {
    for (String blobId : versions.listTarballBlobIds(repositoryNames)) {
      into.putIfAbsent(blobId, onDisk.getOrDefault(blobId, 0L));
    }
  }

  private static List<String> namesOfType(
      List<ArtifactRepository> repositories, RepositoryType type) {
    return repositories.stream()
        .filter(repository -> repository.type == type)
        .map(repository -> repository.name)
        .toList();
  }
}
