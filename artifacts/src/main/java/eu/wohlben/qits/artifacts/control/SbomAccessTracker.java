package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.persistence.SbomDocumentRepository;
import eu.wohlben.qits.blobstore.control.ArtifactAccessTracker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;

/**
 * The SBOM half of access tracking, coalesced on {@link ArtifactAccessTracker#WRITE_WINDOW} —
 * {@link DocsAccessTracker}'s twin, and carved out for the same reason.
 *
 * <p>The coalescing matters here because the expected reader is a service on a schedule:
 * qits-platform-maintenance re-reads the SBOMs of every artifact it tracks, and a scan touching a
 * thousand rows must be a thousand cheap predicate updates at most once an hour, not a write per
 * request per scan.
 */
@ApplicationScoped
public class SbomAccessTracker {

  @Inject SbomDocumentRepository documents;

  /** One stored document, reached by the version-addressed GET. */
  @Transactional
  public void touchDocument(
      String repository, String packageType, String packageName, String version, Instant now) {
    documents.touch(
        repository, packageType, packageName, version, ArtifactAccessTracker.cutoff(now), now);
  }
}
