package eu.wohlben.qits.artifacts.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One published version of one platform daemon — {@code (repository, name, version)} <em>is</em> the
 * identity.
 *
 * <p>This row is what the {@code daemon-binaries} type exists to create. Before it, the ci-daemon
 * binary reached the store through the OCI blob-upload session, which promotes bytes and writes no
 * row by construction: the executable every build downloads was row-less, invisible to every view
 * built on the database, and counted as an orphan. The row is written in the <b>same transaction</b>
 * as the publish, so identity-at-publish holds by construction and no backfill path has to exist for
 * anything published from here on.
 *
 * <p>The binary's <em>bytes</em> are an ordinary {@code BlobStore} blob and {@link #blobId} is its
 * sha256 key, so a daemon dedupes globally with image layers, npm tarballs and maven jars. {@link
 * #sizeBytes} rides beside it — free at stage time — making this the second protocol table the
 * census sizes from the row rather than from disk.
 *
 * <p>Versions are immutable: re-publishing one is {@code 409}, npm's stance. That is what makes the
 * version-addressed download route safe to serve beside the digest-addressed blob route, which is
 * untouched — a version pointer that never moves is as self-verifying as a digest, and latest-wins
 * can never happen (daemon-artifact-identity-plan.md §2.2).
 */
@Entity
@Table(name = "daemon_binary")
@IdClass(DaemonBinaryId.class)
public class DaemonBinary extends PanacheEntityBase {

  /** The {@code daemon-binaries} repository row — the seeded {@code daemons} namespace. */
  @Id public String repository;

  /** The daemon this is a build of, e.g. {@code qits-ci-daemon}. */
  @Id public String name;

  /**
   * Calver from the release train. Adopted rows carry the digest hex itself (⚖5), so the value
   * {@code QITS_CI_DAEMON_VERSION} already holds doubles as a valid version coordinate.
   */
  @Id
  @Column(length = 128)
  public String version;

  @Column(name = "blob_id", nullable = false, length = 64)
  public String blobId;

  /** Free at stage time; the census and the explorer size this type from the row, never from disk. */
  @Column(name = "size_bytes", nullable = false)
  public long sizeBytes;

  /** Server-stamped. A publisher does not get to say when its release happened. */
  @Column(name = "published_at", nullable = false)
  public Instant publishedAt;
}
