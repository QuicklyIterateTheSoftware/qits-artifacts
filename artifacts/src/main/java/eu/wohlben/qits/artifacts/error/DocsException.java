package eu.wohlben.qits.artifacts.error;

import eu.wohlben.qits.blobstore.error.ArtifactsException;

/**
 * A docs error, carrying the status the wire should answer with.
 *
 * <p>The publisher is a release pipeline and the consumer is qits-docs — so, exactly like {@link
 * DaemonException}, {@link MavenException} and {@link NpmException}, the status <em>is</em> the code
 * and the message is plain text. It is never mapped by {@code ArtifactsExceptionMapper}: that is a
 * JAX-RS provider and these are thrown on raw Vert.x routes, where {@code DocsErrors} renders them
 * instead.
 */
public class DocsException extends ArtifactsException {

  public DocsException(int statusCode, String message) {
    super(statusCode, message);
  }
}
