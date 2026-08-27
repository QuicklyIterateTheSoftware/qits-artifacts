package eu.wohlben.qits.artifacts.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.annotations.BatchSize;

/**
 * One published version of one documentation site — {@code (repository, name, version)} <em>is</em>
 * the identity, and it is the only identity this type has.
 *
 * <p><b>This row is the unit of eviction.</b> A bundle is fifty-odd files and the thing that must be
 * evictable is the version, so {@link DocsFile} deliberately has no identity of its own: its rows
 * hang off this one by the same three columns and cascade with it. A GC strategy plans one candidate
 * per row here and never per path, which makes a half-collected site — a version that still lists
 * itself and answers 404 for its assets — unrepresentable rather than merely discouraged.
 *
 * <p>The files' <em>bytes</em> are ordinary {@code BlobStore} blobs, so a bundle dedupes globally
 * with image layers, npm tarballs, maven jars and daemon binaries, and far more often with its own
 * previous versions: fonts and unchanged chunks are byte-identical across releases and are stored
 * once. {@link #totalBytes} is the sum over the bundle <em>as published</em>, so it sizes the site
 * rather than the disk; the disk answer counts distinct blobs and belongs to the census.
 *
 * <p>Versions are immutable: re-publishing one is {@code 409}, {@link DaemonBinary}'s stance. That is
 * what makes a version-addressed URL safe to hand out and to bookmark, and it is why qits-docs can
 * be stateless — {@code latest} is a query over these rows, not a mutable alias someone has to keep
 * correct.
 */
@Entity
@Table(name = "docs_site")
@IdClass(DocsSiteId.class)
public class DocsSite extends PanacheEntityBase {

  /** The {@code docs} repository row — the seeded {@code docs} namespace. */
  @Id public String repository;

  /**
   * The site, e.g. {@code @qits/ui-components}. Scoped and free to nest, because a docs name is
   * whatever namespacing the publishing project already uses — the npm package name where there is
   * one, a project path where there is not.
   */
  @Id public String name;

  /** Calver from the release train, matching the version the same run published elsewhere. */
  @Id
  @Column(length = 128)
  public String version;

  @Column(name = "file_count", nullable = false)
  public int fileCount;

  /** The bundle as published, summed over its files. See the class javadoc on what this is not. */
  @Column(name = "total_bytes", nullable = false)
  public long totalBytes;

  /** Server-stamped. A publisher does not get to say when its release happened. */
  @Column(name = "published_at", nullable = false)
  public Instant publishedAt;

  /**
   * When this version last served a file, coalesced to once an hour; null means never read since
   * tracking began (V11).
   *
   * <p>Any file of the bundle moves it, because the thing being asked for is the site: a reader
   * opening the workbench pulls the index, the chunks and the fonts, and attributing that to fifty
   * separate rows would answer a question nobody asks. The version is what ages out, so the version
   * is what records being wanted.
   */
  @Column(name = "accessed_at")
  public Instant accessedAt;

  /**
   * The flat string metadata map a publisher rode on {@code X-Artifacts-Meta-*} headers —
   * {@code git.branch.name}, {@code git.commit.hash} and whatever else the publisher chose, stored
   * opaquely (the blob plane's stance: blobs address the world by string metadata).
   *
   * <p><b>LAZY is load-bearing.</b> The catalog reads every row of the repository
   * ({@code listAllByRepository}) and touches only name/version/publishedAt — an eager collection
   * there would be one query per site version. The endpoints that do read metadata (one version,
   * one site's versions) initialize it inside their request context, batched.
   */
  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(
      name = "docs_site_metadata",
      joinColumns = {
        @JoinColumn(name = "repository", referencedColumnName = "repository"),
        @JoinColumn(name = "name", referencedColumnName = "name"),
        @JoinColumn(name = "version", referencedColumnName = "version")
      })
  @MapKeyColumn(name = "meta_key")
  @Column(name = "meta_value", length = 4000)
  @BatchSize(size = 50)
  public Map<String, String> metadata = new HashMap<>();
}
