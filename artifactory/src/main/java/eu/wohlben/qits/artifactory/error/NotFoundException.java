package eu.wohlben.qits.artifactory.error;

/** Artifactory error mapped to HTTP 404 by the web layer. */
public class NotFoundException extends ArtifactoryException {

  public NotFoundException(String message) {
    super(404, message);
  }
}
