package eu.wohlben.qits.artifacts.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * The composite key of {@link NpmProxyPackument}. A plain class rather than a record because
 * Hibernate requires a public no-arg constructor on an {@code @IdClass}.
 */
public class NpmProxyPackumentId implements Serializable {

  public String repository;
  public String packageName;

  public NpmProxyPackumentId() {}

  public NpmProxyPackumentId(String repository, String packageName) {
    this.repository = repository;
    this.packageName = packageName;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof NpmProxyPackumentId id
        && Objects.equals(repository, id.repository)
        && Objects.equals(packageName, id.packageName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(repository, packageName);
  }
}
