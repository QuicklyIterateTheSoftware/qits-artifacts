package eu.wohlben.qits.artifactory.error;

/** Artifactory error mapped to HTTP 400 by the web layer. */
public class BadRequestException extends ArtifactoryException {

  public BadRequestException(String message) {
    super(400, message);
  }
}
