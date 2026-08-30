package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The platform's own <b>daemon executables</b>, served at {@code /artifacts/daemons/} by {@code
 * eu.wohlben.qits.daemon}. One of the two types this service contributes to the open registration —
 * no library ships it, because no other service serves it.
 *
 * <p>A protocol profile: a binary arrives on the daemon wire's streaming {@code PUT} and goes
 * straight to {@code BlobStore}, so there is no media type to sniff — {@code MediaTypeSniffer} has
 * no ELF entry and would 400 — and no metadata to require. The validating upload path is refused
 * outright and the cap is zero, "not applicable" rather than "unlimited". The real cap is {@code
 * qits.artifacts.daemon.max-binary-size}.
 *
 * <p><b>It names a role, not a technology</b> (daemon-artifact-identity-plan.md ⚖1). A generic
 * {@code binary} type would collect anything merely compiled and could answer none of the three
 * questions a type has to answer — who publishes here, what GC keeps, what pins an entry — because
 * it would not know what its contents are <em>for</em>. This one does: executables the platform
 * itself downloads and runs, published by release pipelines, pinned by the service that launches
 * them. A future non-daemon binary gets its own type the day it exists.
 *
 * <p>Versions are immutable — re-publishing one is {@code 409}, npm's stance. That is what makes the
 * version-addressed download route safe beside the digest-addressed blob route, and the rows are
 * what turn 64 hex characters back into a readable {@code (name, version)}.
 *
 * <p>The reason this type exists at all is a measured hole: before it, the ci-daemon binary reached
 * the store through the OCI blob-upload session, which promotes bytes and writes no row — so 124 MiB
 * of live, downloaded-every-build executable was row-less, invisible to every database-backed view,
 * and reported as an orphan. {@code daemon_binary} is what the census reads instead.
 */
@ApplicationScoped
public class DaemonBinariesProfile implements RepositoryTypeProfile {

  /**
   * The stored key, verbatim as {@code artifact_repository.type} has carried it since V10 and as
   * {@code ck_artifact_repository_type} lists it. It is a column value, not a name this class is
   * free to change.
   */
  public static final String KEY = "DAEMON_BINARIES";

  @Override
  public String key() {
    return KEY;
  }
}
