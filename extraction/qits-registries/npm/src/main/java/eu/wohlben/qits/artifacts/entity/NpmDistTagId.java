package eu.wohlben.qits.artifacts.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * The composite key of {@link NpmDistTag}. A plain class rather than a record because Hibernate
 * requires a public no-arg constructor on an {@code @IdClass}.
 */
public class NpmDistTagId implements Serializable {

  public String repository;
  public String packageName;
  public String tag;

  public NpmDistTagId() {}

  public NpmDistTagId(String repository, String packageName, String tag) {
    this.repository = repository;
    this.packageName = packageName;
    this.tag = tag;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof NpmDistTagId id
        && Objects.equals(repository, id.repository)
        && Objects.equals(packageName, id.packageName)
        && Objects.equals(tag, id.tag);
  }

  @Override
  public int hashCode() {
    return Objects.hash(repository, packageName, tag);
  }
}
