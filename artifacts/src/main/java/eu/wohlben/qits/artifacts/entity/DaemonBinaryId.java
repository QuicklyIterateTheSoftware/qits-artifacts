package eu.wohlben.qits.artifacts.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * The composite key of {@link DaemonBinary}. A plain class rather than a record because Hibernate
 * requires a public no-arg constructor on an {@code @IdClass}.
 */
public class DaemonBinaryId implements Serializable {

  public String repository;
  public String name;
  public String version;

  public DaemonBinaryId() {}

  public DaemonBinaryId(String repository, String name, String version) {
    this.repository = repository;
    this.name = name;
    this.version = version;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof DaemonBinaryId id
        && Objects.equals(repository, id.repository)
        && Objects.equals(name, id.name)
        && Objects.equals(version, id.version);
  }

  @Override
  public int hashCode() {
    return Objects.hash(repository, name, version);
  }
}
