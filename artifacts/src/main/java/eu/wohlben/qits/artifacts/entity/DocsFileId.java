package eu.wohlben.qits.artifacts.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * The composite key of {@link DocsFile}: its {@link DocsSiteId site} plus the path. A plain class
 * rather than a record because Hibernate requires a public no-arg constructor on an {@code
 * @IdClass}.
 */
public class DocsFileId implements Serializable {

  public String repository;
  public String name;
  public String version;
  public String path;

  public DocsFileId() {}

  public DocsFileId(String repository, String name, String version, String path) {
    this.repository = repository;
    this.name = name;
    this.version = version;
    this.path = path;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof DocsFileId id
        && Objects.equals(repository, id.repository)
        && Objects.equals(name, id.name)
        && Objects.equals(version, id.version)
        && Objects.equals(path, id.path);
  }

  @Override
  public int hashCode() {
    return Objects.hash(repository, name, version, path);
  }
}
