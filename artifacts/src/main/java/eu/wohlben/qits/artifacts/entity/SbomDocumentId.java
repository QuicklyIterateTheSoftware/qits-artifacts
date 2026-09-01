package eu.wohlben.qits.artifacts.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * The composite key of {@link SbomDocument}. A plain class rather than a record because Hibernate
 * requires a public no-arg constructor on an {@code @IdClass}.
 */
public class SbomDocumentId implements Serializable {

  public String repository;
  public String packageType;
  public String packageName;
  public String version;

  public SbomDocumentId() {}

  public SbomDocumentId(String repository, String packageType, String packageName, String version) {
    this.repository = repository;
    this.packageType = packageType;
    this.packageName = packageName;
    this.version = version;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof SbomDocumentId id
        && Objects.equals(repository, id.repository)
        && Objects.equals(packageType, id.packageType)
        && Objects.equals(packageName, id.packageName)
        && Objects.equals(version, id.version);
  }

  @Override
  public int hashCode() {
    return Objects.hash(repository, packageType, packageName, version);
  }
}
