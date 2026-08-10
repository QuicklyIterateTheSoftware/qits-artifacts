package eu.wohlben.qits.artifacts.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * The composite key of {@link MavenProxyMetadata}. A plain class rather than a record because
 * Hibernate requires a public no-arg constructor on an {@code @IdClass}.
 */
public class MavenProxyMetadataId implements Serializable {

  public String repository;
  public String path;

  public MavenProxyMetadataId() {}

  public MavenProxyMetadataId(String repository, String path) {
    this.repository = repository;
    this.path = path;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof MavenProxyMetadataId id
        && Objects.equals(repository, id.repository)
        && Objects.equals(path, id.path);
  }

  @Override
  public int hashCode() {
    return Objects.hash(repository, path);
  }
}
