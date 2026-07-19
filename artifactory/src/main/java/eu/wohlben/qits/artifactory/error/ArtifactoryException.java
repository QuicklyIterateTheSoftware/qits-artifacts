package eu.wohlben.qits.artifactory.error;

/**
 * Base for artifactory errors. Carries an HTTP-ish status code so the web layer can map it to a
 * response without artifactory depending on JAX-RS (the same framework-free stance as {@code
 * domain.error} — but artifactory must not depend on {@code domain}, so it owns its own). The
 * {@code service} module maps these via {@code ArtifactoryExceptionMapper}.
 */
public class ArtifactoryException extends RuntimeException {

  private final int statusCode;

  public ArtifactoryException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public ArtifactoryException(int statusCode, String message, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }
}
