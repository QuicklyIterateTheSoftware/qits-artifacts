package eu.wohlben.qits.artifacts.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An upstream container registry this service mirrors, and the local namespace segment fronting it.
 *
 * <p><b>A row rather than a config key.</b> That is the user's ruling (proxy-pulling-normal-images.md
 * ⚖1) and their reason for it: config keys are invisible. A row has a CRUD API at {@code
 * /artifacts/api/mirror-upstreams} and a page in the explorer, so which registries this one fronts
 * is a thing an operator can see rather than a thing they have to know.
 *
 * <p>{@link #domain} is the identity — the mirror derives the API endpoint from it — and {@link
 * #slug} is what a puller writes: {@code docker pull <host>/quay/quarkus/ubi9-…:jdk-25}. The slug is
 * unique because it is a namespace, and it is a foreign key into {@code artifact_repository} because
 * every upstream is <b>paired</b> with a repository row of type {@link RepositoryType#OCI_MIRROR}.
 * Cached content is ordinary {@code oci_manifest}/{@code oci_tag} rows under that name, so resolving
 * a namespace on a pull is a table read.
 *
 * <p>Deleting an upstream deletes this row and nothing else: the repository row and everything
 * cached under it stay (⚖2, append-only). New misses in that namespace stop being fetchable; what is
 * already cached keeps serving.
 *
 * <p>No credential columns, deliberately. Anonymous upstreams only at launch (⚖3) — a client's
 * {@code docker login} never traverses a pull-through hop, so a private upstream needs a
 * server-side credential, and that arrives as an additive column pair here the day it is needed.
 */
@Entity
@Table(name = "oci_mirror_upstream")
public class OciMirrorUpstream extends PanacheEntityBase {

  /** The registry's domain: {@code docker.io}, {@code quay.io}, {@code registry.access.redhat.com}. */
  @Id public String domain;

  /** The local namespace segment, and the name of the paired {@code OCI_MIRROR} repository row. */
  @Column(nullable = false, unique = true)
  public String slug;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
