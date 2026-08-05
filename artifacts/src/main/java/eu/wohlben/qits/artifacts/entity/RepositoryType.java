package eu.wohlben.qits.artifacts.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Set;

/**
 * A repository's <b>type</b> = its validation/convention profile over the shared blob core: which
 * media types it accepts, which metadata keys it requires, and its per-upload size cap.
 * Deliberately thin — the deferred protocol types (maven, and now npm/docker) slot in as new
 * constants without touching the core.
 *
 * <p>The two CI types serve the golden-diff loop. Their required keys are the pairing/comparison
 * keys the future diff UI needs (branch, commit, flow name + hash, display name, diff hash) plus the
 * media resolution each type can express.
 *
 * <p>{@code OCI_IMAGES} is the first constant to take the other option the seam allows: a protocol
 * type, whose bytes bypass the validating upload path entirely and reach {@code BlobStore} through
 * its own wire routes. Read its javadoc before assuming a new type behaves like the CI ones.
 *
 * <p>Adding a constant is a schema change as well as a code change — {@code artifact_repository.type}
 * carries a check constraint, named {@code ck_artifact_repository_type} since V2.
 */
public enum RepositoryType {

  /** Golden screenshots, diffed by branch. */
  CI_SCREENSHOTS(
      Set.of("image/png", "image/jpeg", "image/svg+xml"),
      Set.of(
          "git.branch.name",
          "git.commit.hash",
          "qits.userflow.name",
          "qits.userflow.hash",
          "qits.display.name",
          "qits.diff.hash",
          "media.resolution.width",
          "media.resolution.height"),
      25L * 1024 * 1024),

  /** Golden videos, diffed by branch. */
  CI_VIDEOS(
      Set.of("video/mp4", "video/webm"),
      Set.of(
          "git.branch.name",
          "git.commit.hash",
          "qits.userflow.name",
          "qits.userflow.hash",
          "qits.display.name",
          "qits.diff.hash",
          "media.resolution.length"),
      // 64 MB: generous for a short compressed golden clip, matched to the global HTTP body ceiling
      // (see service application.properties + docs/issues on the max-body-size tradeoff).
      64L * 1024 * 1024),

  /**
   * Container images, served over the OCI Distribution API at {@code /v2}.
   *
   * <p>Unlike the two CI types this one is <b>not</b> served by {@link
   * eu.wohlben.qits.artifacts.control.BlobService}: its bytes arrive through the registry routes
   * ({@code eu.wohlben.qits.registry}), which talk to {@link
   * eu.wohlben.qits.artifacts.control.BlobStore} directly — no media-type sniffing (a gzipped tar
   * layer sniffs to nothing and would 400), no required metadata keys, no {@code artifact_record}
   * row. A layer is addressed by its digest and nothing else.
   *
   * <p>So both profile fields are <b>empty</b> and the cap is <b>zero</b> — "not applicable", not
   * "unlimited". The empty media-type set is what makes that safe rather than merely unused: a stray
   * {@code POST /artifacts/api/repositories/&lt;an oci repo&gt;/blobs} fails {@code accepts()} and is
   * rejected before {@code BlobService} ever reads {@link #maxBytes()}. And zero rather than a
   * plausible-looking number is deliberate: if this profile ever gains a media type, a zero cap
   * fails loudly at the first byte instead of quietly accepting a gigabyte down a path that was
   * never meant to carry one.
   *
   * <p>The real layer cap is {@code qits.artifacts.oci.max-layer-size} (default 1G), resolved in the
   * registry. It is a config knob and not a constant here because it has to move with {@code
   * quarkus.http.limits.max-body-size} — a deployment's disk budget, not a property of the format.
   */
  OCI_IMAGES(Set.of(), Set.of(), 0L),

  /**
   * A <b>hosted</b> npm registry, served at {@code /artifacts/npm/<repository>} by {@code
   * eu.wohlben.qits.npm}.
   *
   * <p>A protocol type on the {@link #OCI_IMAGES} pattern, and everything that javadoc says about
   * empty profiles and a zero cap holds verbatim: a tarball arrives base64-inflated inside a publish
   * document on a raw Vert.x route and goes straight to {@code BlobStore}, so there is no media type
   * to sniff and no metadata to require, and the empty media-type set is what makes the zero cap
   * safe rather than merely unused. The real cap is {@code qits.artifacts.npm.max-publish-size}.
   *
   * <p>Versions are immutable — re-publishing one is {@code 403}, the npm analog of the registry's
   * append-only stance.
   */
  NPM_PACKAGES(Set.of(), Set.of(), 0L),

  /**
   * A <b>pull-through cache</b> of an upstream npm registry (default {@code
   * https://registry.npmjs.org}), served on the same routes as {@link #NPM_PACKAGES}.
   *
   * <p>Separate from the hosted type rather than a flag on it, following the namespacing rule the
   * OCI mirror already settled: cached upstream content and published content must not share a
   * namespace, and a mirror must reject pushes <em>by type</em> rather than by configuration. So a
   * {@code PUT} here is refused because of what this constant is, not because of how a deployment
   * set it up, and no repository can drift from one meaning to the other — {@code
   * ArtifactRepositoryService.ensure} makes a repository's type immutable.
   */
  NPM_PROXY(Set.of(), Set.of(), 0L),

  /**
   * A <b>pull-through cache</b> of an upstream container registry, served on the same {@code /v2}
   * routes as {@link #OCI_IMAGES}.
   *
   * <p>A protocol type on the {@link #OCI_IMAGES} pattern, and everything that javadoc says about
   * empty profiles and a zero cap holds verbatim — a mirrored layer arrives on the registry's own
   * wire routes and goes straight to {@code BlobStore}, so there is no media type to sniff and no
   * metadata to require. The real cap is {@code qits.artifacts.oci.max-layer-size}, the same one the
   * hosted type is bounded by.
   *
   * <p>One row per registered upstream, named by that upstream's local namespace segment: {@code
   * hub}, {@code quay}, {@code redhat}, paired with an {@code oci_mirror_upstream} row carrying the
   * domain it fronts. So {@code docker pull <host>/quay/quarkus/ubi9-…:jdk-25} reads like what it
   * is, and every future per-upstream property (a credential, a per-upstream TTL) has a row to hang
   * on. Cached content lives in ordinary {@code oci_manifest}/{@code oci_tag} rows under the slug.
   *
   * <p><b>A push here is refused by type</b>, exactly as {@link #NPM_PROXY} refuses a publish:
   * cached upstream content and pushed content must never share a namespace, and no repository can
   * drift from one meaning to the other because {@code ArtifactRepositoryService.ensure} makes a
   * type immutable. The refusal is {@code 405}, not a configuration.
   *
   * <p>Garbage collection <b>evicts</b> what nothing has pulled inside the configured window
   * ({@code OciMirrorGcStrategy} on the cache engine). It used to be append-only pending access
   * tracking (⚖2); that condition is discharged — V9 tracks access on {@code oci_tag} and {@code
   * oci_manifest}, and the settlement configured this type as a cache. The separate type is still
   * what keeps the decision from distorting {@link #OCI_IMAGES}'s rules — a mirror tag like {@code
   * jdk-25} is neither a calver release nor a build sha, and would otherwise be kept by docker's
   * unclassified-means-keep rule and reported as if somebody had decided something. Upstream's
   * releases earn no protection here: version protection is own-ness's, and a cache holds none of
   * ours.
   */
  OCI_MIRROR(Set.of(), Set.of(), 0L),

  /**
   * A <b>hosted</b> maven repository, served at {@code /artifacts/maven/<repository>} by {@code
   * eu.wohlben.qits.maven}.
   *
   * <p>A protocol type on the {@link #OCI_IMAGES} pattern, and everything that javadoc says about
   * empty profiles and a zero cap holds verbatim: a jar or a pom arrives on the maven wire routes
   * and goes straight to {@code BlobStore}, so there is no media type to sniff and no metadata to
   * require, and the empty media-type set is what makes the zero cap safe rather than merely unused.
   * The real cap is {@code qits.artifacts.maven.max-artifact-size}.
   *
   * <p>Release paths are immutable — a re-deploy with different bytes is {@code 403}, the maven
   * analog of the registry's append-only stance. A re-deploy of <em>identical</em> bytes is an
   * idempotent no-op, which content addressing makes free; timestamped snapshot files are unique by
   * construction and take the release rule, and a literal {@code -SNAPSHOT} filename is the one
   * mutable path (maven-repository-plan.md §3.6).
   */
  MAVEN_PACKAGES(Set.of(), Set.of(), 0L),

  /**
   * The platform's own <b>daemon executables</b>, served at {@code /artifacts/daemons/} by {@code
   * eu.wohlben.qits.daemon}.
   *
   * <p>A protocol type on the {@link #OCI_IMAGES} pattern, and everything that javadoc says about
   * empty profiles and a zero cap holds verbatim: a binary arrives on the daemon wire's streaming
   * {@code PUT} and goes straight to {@code BlobStore}, so there is no media type to sniff — {@code
   * MediaTypeSniffer} has no ELF entry and would 400 — and no metadata to require. The empty
   * media-type set is what makes the zero cap safe rather than merely unused. The real cap is
   * {@code qits.artifacts.daemon.max-binary-size}.
   *
   * <p><b>It names a role, not a technology</b> (daemon-artifact-identity-plan.md ⚖1). A generic
   * {@code binary} type would collect anything merely compiled and could answer none of the three
   * questions a type has to answer — who publishes here, what GC keeps, what pins an entry —
   * because it would not know what its contents are <em>for</em>. This one does: executables the
   * platform itself downloads and runs, published by release pipelines, pinned by the service that
   * launches them. A future non-daemon binary gets its own type the day it exists.
   *
   * <p>Versions are immutable — re-publishing one is {@code 409}, npm's stance. That is what makes
   * the version-addressed download route safe beside the digest-addressed blob route, which stays
   * exactly as it was: every existing pin keeps working (⚖2), and the rows are what turn 64 hex
   * characters back into a readable {@code (name, version)}.
   *
   * <p>The reason this type exists at all is a measured hole: before it, the ci-daemon binary
   * reached the store through the OCI blob-upload session, which promotes bytes and writes no row —
   * so 124 MiB of live, downloaded-every-build executable was row-less, invisible to every
   * database-backed view, and reported as an orphan. {@code daemon_binary} is what the census reads
   * instead.
   */
  DAEMON_BINARIES(Set.of(), Set.of(), 0L);

  private final Set<String> allowedMediaTypes;
  private final Set<String> requiredMetadataKeys;
  private final long maxBytes;

  RepositoryType(Set<String> allowedMediaTypes, Set<String> requiredMetadataKeys, long maxBytes) {
    this.allowedMediaTypes = allowedMediaTypes;
    this.requiredMetadataKeys = requiredMetadataKeys;
    this.maxBytes = maxBytes;
  }

  public Set<String> allowedMediaTypes() {
    return allowedMediaTypes;
  }

  public Set<String> requiredMetadataKeys() {
    return requiredMetadataKeys;
  }

  public long maxBytes() {
    return maxBytes;
  }

  public boolean accepts(String mediatype) {
    return allowedMediaTypes.contains(mediatype);
  }

  /**
   * The wire form (kebab-case, e.g. {@code ci-screenshots}) — the enum name isn't the API contract.
   */
  @JsonValue
  public String wireName() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /** Parses the kebab wire form; also tolerant of the raw enum name. */
  @JsonCreator
  public static RepositoryType fromWire(String value) {
    if (value == null) {
      return null;
    }
    return RepositoryType.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
  }
}
