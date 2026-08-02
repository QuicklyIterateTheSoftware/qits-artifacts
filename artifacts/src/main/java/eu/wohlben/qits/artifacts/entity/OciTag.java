package eu.wohlben.qits.artifacts.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A movable pointer from a tag to a manifest digest — the registry's only mutable state.
 *
 * <p>Everything else the registry writes is content-addressed and therefore append-only: blobs,
 * manifest bytes, {@link OciManifest} rows. Re-pushing a tag updates this row and nothing else, so
 * the manifest it used to name stays reachable by digest.
 */
@Entity
@Table(name = "oci_tag")
@IdClass(OciTagId.class)
public class OciTag extends PanacheEntityBase {

  @Id public String repository;

  @Id
  @Column(name = "image_name")
  public String imageName;

  @Id
  @Column(length = 128)
  public String tag;

  @Column(name = "manifest_digest", nullable = false, length = 64)
  public String manifestDigest;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;

  @Column(name = "accessed_at")
  public Instant accessedAt;
}
