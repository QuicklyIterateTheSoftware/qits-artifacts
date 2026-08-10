package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.persistence.ArtifactRecordRepository;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.DaemonBinaryRepository;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Collection;
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
 * {@code diskTotal = ociUnion + npmPublished + npmProxyTarballs + mavenPublished + mavenProxy +
 * daemonBinaries + orphans}.
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
 *   <li>{@code npm-packages} — {@code npm_version.tarball_blob_id}, sized from disk because there is
 *       no size column.
 *   <li>{@code maven-packages} — {@code maven_artifact.blob_id}, sized from the row: that table is
 *       the one protocol table whose size was free at stage time, so no disk read and no null size
 *       like npm's. Attribution runs off the repository row's type, exactly like the CI records
 *       below.
 *   <li>{@code daemon-binaries} — {@code daemon_binary.blob_id}, sized from the row for the same
 *       reason maven's is. This is the set that made {@code orphanBytes} honest: every row-less
 *       blob the store held was a ci-daemon build pushed through the blob-upload session, which
 *       writes no row, so the census reported a live executable as an orphan.
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
 * identity row names appears in {@link Census#rowless()}, and no strategy may ever release one: that
 * pool used to be three ELF binaries uploaded through the OCI blob-upload session, one of which is
 * the CI daemon every build downloads. The {@code daemon-binaries} type is what gives those bytes a
 * row; adopting the three already on the volume is an ops action, so until it runs they stay here,
 * reported and left alone.
 *
 * <p><b>The types are open, so this class enumerates none of them.</b> It reads each repository
 * row's own type key and files that repository's blobs under it, which is why nothing here changed
 * when the cache types left for qits-platform-mirror — a deployment with no rows of a type simply
 * has no live set for it.
 */
@ApplicationScoped
public class LiveBlobCensus {

  @Inject ArtifactRepositoryRepository repositories;
  @Inject ArtifactRecordRepository records;
  @Inject OciManifestRepository manifests;
  @Inject NpmVersionRepository versions;
  @Inject MavenArtifactRepository mavenArtifacts;
  @Inject DaemonBinaryRepository daemonBinaries;
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
   * @param liveByType STORED type key ({@code OCI_IMAGES}) to blob id to the size that type knows
   *     for it — declared manifest sizes for OCI, disk sizes for npm tarballs, row sizes for CI
   *     records. Keyed by the row's own key rather than by an enum, because the type set is open
   * @param ociPerImageSumBytes the per-image unions added up, which double-counts what images share
   *     and is reported beside the union rather than instead of it
   */
  public record Census(
      Instant takenAt,
      Map<String, Long> onDisk,
      Map<String, Map<String, Long>> liveByType,
      long ociPerImageSumBytes) {

    public Census {
      onDisk = Map.copyOf(onDisk);
      Map<String, Map<String, Long>> copy = new HashMap<>();
      liveByType.forEach((type, live) -> copy.put(type, Map.copyOf(live)));
      liveByType = Map.copyOf(copy);
    }

    /** Every blob this type still reaches, with the size that type knows for it. */
    public Map<String, Long> live(String type) {
      return liveByType.getOrDefault(type, Map.of());
    }

    /** The type's union, summed once — never a sum over its identities. */
    public long liveBytes(String type) {
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
    Map<String, Map<String, Long>> live = new HashMap<>();

    List<ArtifactRepository> all = repositories.listAll();
    long perImageSum = 0;
    for (ArtifactRepository repository : all) {
      // Every table is read for every repository and attributed to that repository's OWN type key.
      // A repository of the wrong type simply has no rows in a table — an npm root holds no
      // manifests — so nothing here needs to know which types exist, which is what lets the type
      // set be open. The tables answer.
      Map<String, Long> blobs = live.computeIfAbsent(repository.type, type -> new HashMap<>());
      // The OCI half: the manifest closure, walked per image so the per-image sum can be compared
      // against the union — the explorer's "the two differ by the layers two images share" line.
      for (String image : manifests.listImageNames(repository.name)) {
        Map<String, Long> perImage = footprints.union(manifests.listByImage(repository.name, image));
        perImageSum += OciManifestFootprints.sum(perImage);
        perImage.forEach(blobs::putIfAbsent);
      }
      for (Object[] blob : records.listDistinctBlobs(repository.name)) {
        blobs.putIfAbsent((String) blob[0], (Long) blob[1]);
      }
      // The maven half. Sized from the row, the one protocol table that has the size.
      for (Object[] blob : mavenArtifacts.listDistinctBlobs(repository.name)) {
        blobs.putIfAbsent((String) blob[0], (Long) blob[1]);
      }
      // The daemon half, for the sharpest reason in this method: these bytes used to BE the orphan
      // pool. Every one of the store's 124 MiB of row-less blobs was a ci-daemon build, uploaded
      // through the OCI blob-upload session, which writes no row — so orphanBytes reported a live,
      // downloaded-every-build executable as garbage-shaped. With rows the census sees it natively.
      for (Object[] blob : daemonBinaries.listDistinctBlobs(repository.name)) {
        blobs.putIfAbsent((String) blob[0], (Long) blob[1]);
      }
      // The npm half: distinct tarballs, sized from disk because npm_version has no size column.
      for (String blobId : versions.listTarballBlobIds(List.of(repository.name))) {
        blobs.putIfAbsent(blobId, onDisk.getOrDefault(blobId, 0L));
      }
    }

    return new Census(Instant.now(), onDisk, live, perImageSum);
  }
}
