package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.DocsFile;
import eu.wohlben.qits.artifacts.entity.DocsFileId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link DocsFile}, keyed by its site's three columns plus the path. */
@ApplicationScoped
public class DocsFileRepository implements PanacheRepositoryBase<DocsFile, DocsFileId> {

  public Optional<DocsFile> findOne(
      String repository, String name, String version, String path) {
    return findByIdOptional(new DocsFileId(repository, name, version, path));
  }

  /**
   * The distinct blobs one published version references — what a GC candidate carries as its blob
   * set.
   *
   * <p>Distinct within the version, because a bundle may ship the same bytes at two paths; not
   * distinct across versions, which is the point — a blob another version also names is retained by
   * that version's candidate, and reconciling the two is the sweep's job rather than this query's.
   */
  public List<String> listBlobIds(String repository, String name, String version) {
    return getEntityManager()
        .createQuery(
            "select distinct f.blobId from DocsFile f where f.repository = :repository"
                + " and f.name = :name and f.version = :version",
            String.class)
        .setParameter("repository", repository)
        .setParameter("name", name)
        .setParameter("version", version)
        .getResultList();
  }

  /**
   * One published version's paths, sorted — what the version document lists so a reader can tell a
   * bundle's shape (an {@code index.html} site, a directory of markdown) without probing for files
   * it has to guess the names of.
   */
  public List<String> listPaths(String repository, String name, String version) {
    return getEntityManager()
        .createQuery(
            "select f.path from DocsFile f where f.repository = :repository"
                + " and f.name = :name and f.version = :version order by f.path",
            String.class)
        .setParameter("repository", repository)
        .setParameter("name", name)
        .setParameter("version", version)
        .getResultList();
  }

  /**
   * The distinct blobs a repository references, with their sizes — the docs half of a size union.
   *
   * <p>{@code distinct} is doing real work here, unlike in the daemon and maven queries it mirrors:
   * a docs bundle's fonts and unchanged chunks repeat across every version that references them, so
   * the row count and the blob count differ by design. Sized from the row rather than from disk,
   * because the size was free at stage time.
   */
  public List<Object[]> listDistinctBlobs(String repository) {
    return getEntityManager()
        .createQuery(
            "select distinct f.blobId, f.sizeBytes from DocsFile f"
                + " where f.repository = :repository",
            Object[].class)
        .setParameter("repository", repository)
        .getResultList();
  }

  /**
   * The same union narrowed to one site — what a site's own size is.
   *
   * <p>Narrowed in the query rather than filtered in Java, because {@code distinct} has to run over
   * the narrowed set: the font every version of this site ships is one blob and is counted once
   * here, while the same font shipped by a <em>different</em> site must not be added at all.
   * Summing the per-site answers therefore over-counts against {@link #listDistinctBlobs(String)},
   * which is the honest arithmetic and not a bug.
   */
  public List<Object[]> listDistinctBlobs(String repository, String name) {
    return getEntityManager()
        .createQuery(
            "select distinct f.blobId, f.sizeBytes from DocsFile f"
                + " where f.repository = :repository and f.name = :name",
            Object[].class)
        .setParameter("repository", repository)
        .setParameter("name", name)
        .getResultList();
  }

  /**
   * One site's distinct blobs <b>tagged with the version that names them</b> — every version's size
   * in a single query.
   *
   * <p>{@code (version, blobId, sizeBytes)}, distinct within a version because a bundle may ship the
   * same bytes at two paths, and <em>not</em> distinct across versions, which is the point: the
   * shared font appears once per version that carries it, so folding these rows per version gives
   * each version its own union and folding them all together would not. One query rather than one
   * per version, on {@code listAllByRepository}'s reasoning — a site holds a handful of retained
   * versions, so the grouping is free in Java and the round trips are not.
   */
  public List<Object[]> listDistinctBlobsByVersion(String repository, String name) {
    return getEntityManager()
        .createQuery(
            "select distinct f.version, f.blobId, f.sizeBytes from DocsFile f"
                + " where f.repository = :repository and f.name = :name",
            Object[].class)
        .setParameter("repository", repository)
        .setParameter("name", name)
        .getResultList();
  }
}
