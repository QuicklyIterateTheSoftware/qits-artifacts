package eu.wohlben.qits.artifacts.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * The composite key of {@link OciManifest}. A plain class rather than a record because Hibernate
 * requires a public no-arg constructor on an {@code @IdClass}.
 */
public class OciManifestId implements Serializable {

  public String repository;
  public String imageName;
  public String digest;

  public OciManifestId() {}

  public OciManifestId(String repository, String imageName, String digest) {
    this.repository = repository;
    this.imageName = imageName;
    this.digest = digest;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof OciManifestId id
        && Objects.equals(repository, id.repository)
        && Objects.equals(imageName, id.imageName)
        && Objects.equals(digest, id.digest);
  }

  @Override
  public int hashCode() {
    return Objects.hash(repository, imageName, digest);
  }
}
