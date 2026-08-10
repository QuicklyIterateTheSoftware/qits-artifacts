package eu.wohlben.qits.artifacts.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * The composite key of {@link NpmVersion}. A plain class rather than a record because Hibernate
 * requires a public no-arg constructor on an {@code @IdClass}.
 */
public class NpmVersionId implements Serializable {

  public String repository;
  public String packageName;
  public String version;

  public NpmVersionId() {}

  public NpmVersionId(String repository, String packageName, String version) {
    this.repository = repository;
    this.packageName = packageName;
    this.version = version;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof NpmVersionId id
        && Objects.equals(repository, id.repository)
        && Objects.equals(packageName, id.packageName)
        && Objects.equals(version, id.version);
  }

  @Override
  public int hashCode() {
    return Objects.hash(repository, packageName, version);
  }
}
