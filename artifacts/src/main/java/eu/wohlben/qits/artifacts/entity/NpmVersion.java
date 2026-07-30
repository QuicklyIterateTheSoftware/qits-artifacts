package eu.wohlben.qits.artifacts.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One version of one npm package, in one repository — published here, or pulled through the proxy.
 *
 * <p>The tarball's <em>bytes</em> are an ordinary {@code BlobStore} blob and {@link #tarballBlobId}
 * is its sha256 key, so npm tarballs dedupe globally with layers, screenshots and everything else.
 * npm's own two hashes live beside it rather than instead of it: {@link #shasum} is sha1 and {@link
 * #integrity} a base64 sha512 SRI string, neither addressable by the store, both verified end to
 * end by the client from the packument this row is re-emitted in.
 *
 * <p>{@link #manifestJson} is the version's manifest exactly as it arrived — every field npm cares
 * about, none of them modelled here, so a manifest gains a key without a schema change. Only {@code
 * dist} is replaced when the packument is assembled, because a tarball URL is a property of the
 * request's authority rather than of the package.
 *
 * <p>A row is immutable and there is no delete: re-publishing a version is {@code 403}. That is the
 * same append-only stance {@link OciManifest} takes, and the reason {@link NpmDistTag} exists.
 */
@Entity
@Table(name = "npm_version")
@IdClass(NpmVersionId.class)
public class NpmVersion extends PanacheEntityBase {

  @Id public String repository;

  @Id
  @Column(name = "package_name")
  public String packageName;

  @Id
  @Column(length = 128)
  public String version;

  @Column(name = "tarball_blob_id", nullable = false, length = 64)
  public String tarballBlobId;

  /** The base64 sha512 SRI string npm verifies the download against. Upstream's, for a proxy. */
  public String integrity;

  /** The legacy sha1 hex hash, still emitted because old clients still read it. */
  public String shasum;

  @Lob
  @Column(name = "manifest_json", nullable = false)
  public String manifestJson;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
