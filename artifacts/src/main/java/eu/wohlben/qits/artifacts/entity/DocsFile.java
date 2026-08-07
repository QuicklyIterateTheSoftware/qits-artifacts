package eu.wohlben.qits.artifacts.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * One file inside one published {@link DocsSite} — {@code assets/iframe-BPG5Eshk.js},
 * {@code index.html}, a font.
 *
 * <p><b>It has no identity of its own, deliberately.</b> The key is the site's three columns plus
 * the path, and the foreign key cascades, so a file row cannot exist without its site and cannot
 * outlive it. That is the schema saying what the type means: a version is what gets published and a
 * version is what gets evicted, and nothing here is separately reachable by a retention rule.
 *
 * <p>{@link #blobId} is an ordinary {@code BlobStore} sha256 key, which is where the dedupe comes
 * from — the eight fonts and every unchanged chunk of a Storybook bundle are byte-identical from one
 * release to the next and are stored once however many versions reference them.
 *
 * <p>{@link #mediaType} is resolved from the file <em>extension</em> at publish time, not by {@code
 * MediaTypeSniffer}: the sniffer has no {@code woff2} entry and would reject exactly the files a
 * static site is made of. It is stored rather than re-derived so the serve path stays one row read
 * and a {@code sendFile}.
 */
@Entity
@Table(name = "docs_file")
@IdClass(DocsFileId.class)
public class DocsFile extends PanacheEntityBase {

  @Id public String repository;

  @Id public String name;

  @Id
  @Column(length = 128)
  public String version;

  /** Relative to the bundle root, no leading slash and no dot-segments — the publish enforces both. */
  @Id
  @Column(length = 1024)
  public String path;

  @Column(name = "blob_id", nullable = false, length = 64)
  public String blobId;

  @Column(name = "size_bytes", nullable = false)
  public long sizeBytes;

  @Column(name = "media_type", nullable = false, length = 128)
  public String mediaType;
}
