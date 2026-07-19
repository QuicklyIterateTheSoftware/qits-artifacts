package eu.wohlben.qits.artifactory.error;

/** Artifactory error mapped to HTTP 413 by the web layer (per-type upload size cap exceeded). */
public class PayloadTooLargeException extends ArtifactoryException {

  public PayloadTooLargeException(String message) {
    super(413, message);
  }
}
