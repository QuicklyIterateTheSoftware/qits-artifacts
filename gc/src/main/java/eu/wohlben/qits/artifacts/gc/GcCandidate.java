package eu.wohlben.qits.artifacts.gc;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * One identity of one type, reduced to the five facts a generic engine reads.
 *
 * <p>This is the whole translation between a repository type and the two engines. What an identity
 * <em>is</em> — a tag, a version, a deployed path, a record row — stays inside its {@link
 * GcTypeAdapter}; what an engine sees is this record, and nothing here names a protocol.
 *
 * @param repository the {@code artifact_repository} row it lives in
 * @param identity the type's own coordinate, spelled the way that type's tools spell it, so a
 *     report can be looked up without translating
 * @param group the identity group the release rule counts within — the package, the image, the
 *     {@code (group, artifact)} coordinate, the daemon's name. Only {@link OwnArtifactsStrategy}
 *     reads it; a cache has no groups worth counting.
 * @param released whether this identity is a <b>release</b> in its own type's meaning. That meaning
 *     differs per type by design (a version string with no prerelease part for npm, a calver tag
 *     beside a sha tag for docker), which is exactly why the adapter answers it rather than the
 *     engine deciding.
 * @param lastAccessAt the <b>effective access time</b>: {@code max(created/published/fetched,
 *     accessed_at)}. Creation counts as a first access, so a freshly published artifact nobody has
 *     read yet is young rather than never-read, and null therefore never reaches an engine.
 * @param blobs every blob this identity names. The engines put them in the released or the retained
 *     set; which of them may actually be unlinked stays {@link BlobSweep}'s answer alone.
 */
public record GcCandidate(
    String repository,
    String identity,
    String group,
    boolean released,
    Instant lastAccessAt,
    Set<String> blobs) {

  public GcCandidate {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(group, "group");
    Objects.requireNonNull(
        lastAccessAt,
        "lastAccessAt: an adapter must fold creation into the access time, so that a row with no"
            + " read yet reads as young rather than as unknown");
    blobs = Set.copyOf(blobs);
  }

  /** Whether this identity was last touched before the cut — the access rule, said once. */
  boolean unaccessedSince(Instant cut) {
    return lastAccessAt.isBefore(cut);
  }
}
