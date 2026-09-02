package eu.wohlben.qits.artifacts.error;

import eu.wohlben.qits.blobstore.error.ArtifactsException;

/**
 * An SBOM-store error, carrying the status the wire should answer with.
 *
 * <p>The publisher is a release pipeline reading a status and the consumer is
 * qits-platform-maintenance, so — exactly like {@link DaemonException} and {@link DocsException} —
 * the status <em>is</em> the code and the message is plain text. It is never mapped by {@code
 * ArtifactsExceptionMapper}: that is a JAX-RS provider and these are thrown on raw Vert.x routes,
 * where {@code SbomErrors} renders them instead.
 */
public class SbomException extends ArtifactsException {

  public SbomException(int statusCode, String message) {
    super(statusCode, message);
  }
}
