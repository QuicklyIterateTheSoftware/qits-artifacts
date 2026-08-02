package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.persistence.ArtifactRecordRepository;
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

  private static Instant cutoff(Instant now) {
    return now.minus(WRITE_WINDOW);
  }
}
