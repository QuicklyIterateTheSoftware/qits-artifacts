package eu.wohlben.qits.artifacts.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One stored CycloneDX document — {@code (repository, packageType, packageName, version)}
 * <em>is</em> the identity, and it is the {@code SoftwareRelease} identity verbatim.
 *
 * <p>That equality is the whole design: qits-ci announces a green release once per declared artifact
 * as {@code (packageType, packageName, version)}, and a consumer of that event resolves the SBOM by
 * the same three values with no translation step. The document's <em>bytes</em> are an ordinary
 * {@code BlobStore} blob and {@link #blobId} is its sha256 key, so identical documents dedupe
 * globally like every other byte in this store.
 *
 * <p>Identity is first-write-wins: a re-PUT of an existing identity stores nothing and answers
 * {@code alreadyPublished} — a replayed release build converges rather than failing its SBOM step.
 * The reasoning is on {@code SbomRegistryService.publish}.
 */
@Entity
@Table(name = "sbom_document")
@IdClass(SbomDocumentId.class)
public class SbomDocument extends PanacheEntityBase {

  /** The {@code sboms} repository row — the seeded namespace. */
  @Id public String repository;

  /** The declared artifact type, verbatim from the pipeline: {@code npm|maven|docker|daemon}. */
  @Id
  @Column(name = "package_type", length = 16)
  public String packageType;

  /**
   * The unqualified declared name — {@code qits/qits-artifacts}, {@code @qits/ui-components},
   * {@code eu.wohlben.qits:qits-eventstream} — exactly as {@code SoftwareRelease.packageName}
   * travels it.
   */
  @Id
  @Column(name = "package_name", length = 512)
  public String packageName;

  @Id
  @Column(length = 128)
  public String version;

  @Column(name = "blob_id", nullable = false, length = 64)
  public String blobId;

  /** Free at stage time; the census and the explorer size this type from the row, never from disk. */
  @Column(name = "size_bytes", nullable = false)
  public long sizeBytes;

  /** The document's own {@code specVersion} ({@code 1.4}–{@code 1.6}), echoed on every read. */
  @Column(name = "spec_version", nullable = false, length = 8)
  public String specVersion;

  /** Server-stamped. A publisher does not get to say when its release happened. */
  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  /**
   * When a GET last served this document, coalesced to once an hour; null means never read since
   * stored. qits-platform-maintenance's re-reads are what move it, which is what keeps a
   * live-tracked artifact's SBOM out of the GC window.
   */
  @Column(name = "accessed_at")
  public Instant accessedAt;
}
