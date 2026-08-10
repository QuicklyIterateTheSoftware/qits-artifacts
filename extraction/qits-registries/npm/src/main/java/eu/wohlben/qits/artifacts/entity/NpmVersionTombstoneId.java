package eu.wohlben.qits.artifacts.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * The composite key of {@link NpmVersionTombstone} — the same {@code (repository, packageName,
 * version)} triple {@link NpmVersionId} carries, because it is the same identity. A plain class
 * rather than a record because Hibernate requires a public no-arg constructor on an {@code
 * @IdClass}.
 */
public class NpmVersionTombstoneId implements Serializable {

  public String repository;
  public String packageName;
  public String version;

  public NpmVersionTombstoneId() {}

  public NpmVersionTombstoneId(String repository, String packageName, String version) {
    this.repository = repository;
    this.packageName = packageName;
    this.version = version;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof NpmVersionTombstoneId id
        && Objects.equals(repository, id.repository)
        && Objects.equals(packageName, id.packageName)
        && Objects.equals(version, id.version);
  }

  @Override
  public int hashCode() {
    return Objects.hash(repository, packageName, version);
  }
}
