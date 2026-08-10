package eu.wohlben.qits.artifacts.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A manifest known to one {@code (repository, image)} namespace.
 *
 * <p>The manifest's <em>bytes</em> are an ordinary {@code BlobStore} blob — manifests are
 * content-addressed JSON like everything else. This row is its <em>identity</em>, and it exists for
 * three reasons, none of which a tag table covers:
 *
 * <ul>
 *   <li>The blob store dedupes globally, so without a per-name row a manifest pushed to one
 *       repository would be servable from every other.
 *   <li>{@link #mediaType} is what clients dispatch on, and a digest-addressed GET has to answer
 *       with it — storing it beats re-parsing the JSON on every request.
 *   <li>An index's child manifests are pushed <b>by digest, untagged</b>, and a pull then asks for
 *       them by digest. With only tags they would be unresolvable and every multi-arch pull would
 *       404.
 * </ul>
 *
 * <p>{@link #digest} is the bare 64-hex form {@code BlobStore} speaks, not the wire's {@code
 * sha256:…}.
 */
@Entity
@Table(name = "oci_manifest")
@IdClass(OciManifestId.class)
public class OciManifest extends PanacheEntityBase {

  @Id public String repository;

  @Id
  @Column(name = "image_name")
  public String imageName;

  @Id
  @Column(length = 64)
  public String digest;

  @Column(name = "media_type", nullable = false)
  public String mediaType;

  @Column(name = "size_bytes", nullable = false)
  public long size;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "accessed_at")
  public Instant accessedAt;
}
