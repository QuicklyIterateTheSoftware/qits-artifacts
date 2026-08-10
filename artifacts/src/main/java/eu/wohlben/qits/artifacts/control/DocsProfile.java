package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.RepositoryTypeProfile;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Published <b>documentation sites</b> — a built static bundle per version, served at {@code
 * /artifacts/docs/} by {@code eu.wohlben.qits.docs} and fronted for humans by qits-docs. The second
 * of the two types this service contributes to the open registration.
 *
 * <p>A protocol profile on {@link DaemonBinariesProfile}'s pattern: a bundle arrives as one {@code
 * .tar.gz} on a streaming {@code PUT} and its entries go straight to {@code BlobStore}, so there is
 * no single media type to sniff and no metadata to require. The real cap is {@code
 * qits.artifacts.docs.max-bundle-size}.
 *
 * <p><b>It names a role, not a file format.</b> A generic {@code static-files} type would collect
 * anything merely servable and could answer none of the three questions a type has to answer. This
 * one does: <b>who publishes</b> — the release pipeline of a repository that has documentation to
 * ship, declaring {@code {type: docs}} in its trigger file; <b>what GC keeps</b> — the newest
 * versions of every site, the rest ageing out unaccessed; <b>what pins an entry</b> — nothing
 * outside this service yet, because qits-docs is a stateless view that resolves {@code latest} from
 * these rows rather than holding a pointer of its own.
 *
 * <p><b>A version is the unit, never a file.</b> {@code docs_site} is the identity and the only
 * thing a GC strategy sees; {@code docs_file} rows hang off it and go with it. A bundle's files are
 * ordinary deduplicated blobs, so an asset shared with a version that survives is kept by that
 * version's rows.
 *
 * <p>Versions are immutable — re-publishing one is {@code 409}, for {@link DaemonBinariesProfile}'s
 * reason: a second publish at one version means the version was reused or the release ran twice.
 */
@ApplicationScoped
public class DocsProfile implements RepositoryTypeProfile {

  /**
   * The stored key, verbatim as {@code artifact_repository.type} has carried it since V12 and as
   * {@code ck_artifact_repository_type} lists it. A column value, not a name this class is free to
   * change.
   */
  public static final String KEY = "DOCS";

  @Override
  public String key() {
    return KEY;
  }
}
