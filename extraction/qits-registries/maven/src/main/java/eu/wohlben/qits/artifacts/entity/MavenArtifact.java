package eu.wohlben.qits.artifacts.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One deployed file of one maven repository — the path <em>is</em> the identity.
 *
 * <p>The maven wire ({@code eu.wohlben.qits.maven}) is a dumb path store on the maven layout, and
 * this row is all it persists: the path relative to the repository root is the only lookup a GET, a
 * PUT or a metadata derivation ever needs. Both of maven's documents stay out of the schema, the
 * packument precedent at two levels — {@code maven-metadata.xml} is assembled per request from these
 * rows and never stored, so it cannot become a second source of truth, and checksums are derived at
 * GET and verified at PUT, never stored either.
 *
 * <p>The file's <em>bytes</em> are an ordinary {@code BlobStore} blob and {@link #blobId} is its
 * sha256 key, so jars dedupe globally with image layers, npm tarballs and everything else. {@link
 * #sizeBytes} rides beside it — free at stage time — which makes this the one protocol table the
 * census sizes from the row rather than from disk.
 *
 * <p>Release paths are immutable: a re-deploy with different bytes is {@code 403}. Timestamped
 * snapshot files are unique by construction and take the same rule; a literal {@code -SNAPSHOT}
 * filename is the one mutable path a row may be rewritten for (maven-repository-plan.md §3.6).
 */
@Entity
@Table(name = "maven_artifact")
@IdClass(MavenArtifactId.class)
public class MavenArtifact extends PanacheEntityBase {

  @Id public String repository;

  /** The full maven-layout path relative to the repository root: {@code eu/wohlben/qits/…/x.jar}. */
  @Id
  @Column(length = 1024)
  public String path;

  @Column(name = "blob_id", nullable = false, length = 64)
  public String blobId;

  /** Free at stage time; the census and the explorer size this type from the row, never from disk. */
  @Column(name = "size_bytes", nullable = false)
  public long sizeBytes;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  /**
   * When a GET last served this file, coalesced to once an hour; null means never read since
   * tracking began (V11). The derived documents move nothing: {@code maven-metadata.xml} and every
   * checksum are computed per request and are not this row's bytes, so an access is a read of the
   * stored file itself.
   */
  @Column(name = "accessed_at")
  public Instant accessedAt;
}
