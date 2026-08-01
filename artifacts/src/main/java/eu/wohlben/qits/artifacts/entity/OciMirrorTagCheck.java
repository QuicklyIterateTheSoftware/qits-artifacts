package eu.wohlben.qits.artifacts.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * When a mirrored tag was last checked against its upstream — the mirror's whole freshness state.
 *
 * <p>A tag is the one mirrored thing that mutates: {@code jdk-25} and {@code 9.6} move upstream
 * under toolchain and security updates, while manifests-by-digest and blobs can never mean anything
 * else and are kept forever. So this row is the OCI analogue of {@code npm_proxy_packument}'s {@code
 * fetched_at}, minus the document: the bytes already live in the blob store and the pointer already
 * lives in {@link OciTag}, and duplicating either here would be a second answer to a settled
 * question.
 *
 * <p>{@link #checkedAt} is moved by <b>two</b> different events, which is why it is not called
 * {@code fetched_at}: a fetch that stored new bytes, and a {@code HEAD} that found the digest
 * unchanged. The second is the common one and the cheap one — a registry {@code HEAD} returns
 * {@code Docker-Content-Digest}, and Docker Hub does not count one against its pull limit — so
 * revalidation costs nothing and a short TTL is affordable.
 *
 * <p>A row is written only for a tag in an {@code OCI_MIRROR} namespace. There is deliberately no
 * row when the upstream could not be reached: leaving the tag stale is what makes the next request
 * try again, and touching it on a failure would hide an outage for a whole TTL.
 */
@Entity
@Table(name = "oci_mirror_tag_check")
@IdClass(OciMirrorTagCheckId.class)
public class OciMirrorTagCheck extends PanacheEntityBase {

  @Id public String repository;

  @Id
  @Column(name = "image_name")
  public String imageName;

  @Id
  @Column(length = 128)
  public String tag;

  @Column(name = "checked_at", nullable = false)
  public Instant checkedAt;
}
