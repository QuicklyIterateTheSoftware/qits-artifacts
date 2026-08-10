package eu.wohlben.qits.artifacts.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * The composite key of {@link OciMirrorTagCheck} — the same shape as {@link OciTagId}, because it
 * names the same thing. A plain class rather than a record because Hibernate requires a public
 * no-arg constructor on an {@code @IdClass}.
 */
public class OciMirrorTagCheckId implements Serializable {

  public String repository;
  public String imageName;
  public String tag;

  public OciMirrorTagCheckId() {}

  public OciMirrorTagCheckId(String repository, String imageName, String tag) {
    this.repository = repository;
    this.imageName = imageName;
    this.tag = tag;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof OciMirrorTagCheckId id
        && Objects.equals(repository, id.repository)
        && Objects.equals(imageName, id.imageName)
        && Objects.equals(tag, id.tag);
  }

  @Override
  public int hashCode() {
    return Objects.hash(repository, imageName, tag);
  }
}
