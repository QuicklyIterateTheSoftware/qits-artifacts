package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.SbomProfile;
import eu.wohlben.qits.artifacts.control.SbomRegistryCollection;
import eu.wohlben.qits.artifacts.entity.SbomDocument;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.artifacts.persistence.SbomDocumentRepository;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Published software bills of materials, as facts: one stored <b>document</b> is one identity, its
 * coordinate is the {@code SoftwareRelease} coordinate, and one blob carries it.
 *
 * <h2>A document is the identity, and it is the release's own name</h2>
 *
 * <p>{@link #enumerate} emits one candidate per {@code sbom_document} row, spelled {@code
 * packageType/packageName@version} — the three values the release event travels, in the order the
 * wire spells them at {@code /artifacts/sboms/{packageType}/{packageName}/-/{version}}. A report
 * line is therefore looked up without translating anything.
 *
 * <p>The version comes <b>last</b> for one reason: a package name contains {@code @} (the scoped
 * npm spelling {@code @qits/ui-components}), {@code /} ({@code qits/qits-artifacts}) and {@code :}
 * ({@code eu.wohlben.qits:qits-eventstream}), so no leading delimiter is safe — but a version cannot
 * contain an {@code @} at all: {@code SbomPaths}' {@code VERSION} charset is
 * {@code [A-Za-z0-9][A-Za-z0-9._+-]*}. So the LAST {@code @} separates them, always, and {@link
 * #versionOf} is the only place that is spelled.
 *
 * <p>A candidate's blob set is the one blob the row names. Documents dedupe globally like every
 * other byte here — two identical SBOMs are one blob — and the retained-set union is what keeps the
 * shared bytes when only one of the two rows dies.
 *
 * <h2>A release is a calver row, and a sha row is not one</h2>
 *
 * <p>{@link GcCandidate#released()} answers {@code CALVER.matches(version)}, the same per-type
 * distinction the docs type draws. The release pipelines publish calver versions and their last step
 * is the SBOM PUT, so a calver document is the bill of materials of a real release. Anything else —
 * a per-commit sha, a local experiment, a prerelease spelling — is a working artifact: it never
 * occupies a belt slot and lives exactly as long as the access window keeps it, from {@code
 * max(created_at, accessed_at)}, so a freshly published document is always young.
 *
 * <h2>Nothing pins an SBOM, and that is a decision</h2>
 *
 * <p>{@link #pinnedBy} is not overridden. What keeps a live artifact's document is that
 * qits-platform-maintenance <b>re-reads it</b> on its scan cadence, and every such read moves {@code
 * accessed_at} — so the fact "this artifact is still tracked" already reaches this engine as
 * access. A pin source answering "what maintenance tracks" would restate that same basis in a second
 * place and let the two disagree; the one that lost would be the one deleting rows.
 *
 * <p>The <b>digest</b> floor still applies, as it does to every own type: {@code OwnGcStrategy}
 * checks pinned blob digests under whatever this adapter answers, and a document's bytes are an
 * ordinary deduplicated blob that something else may pin.
 *
 * <h2>Effective access</h2>
 *
 * <p>{@code max(created_at, accessed_at)} — V3's column, moved by a GET and coalesced hourly by
 * {@code SbomAccessTracker}, so a scan that reads a hundred documents writes a hundred rows once an
 * hour rather than on every request. Publication counts as the first access, which is what makes a
 * document published minutes ago read as young rather than as never-read.
 */
@Singleton
public class SbomGcAdapter implements GcTypeAdapter {

  /** The separator between a document's coordinate and its version, as the identity spells it. */
  static final String AT = "@";

  /** A calver release version: {@code <year>.<month><day>.<time>}. */
  static final Pattern CALVER = Pattern.compile("\\d{4}\\.\\d{1,4}\\.\\d+");

  @Inject ArtifactRepositoryRepository repositories;
  @Inject SbomDocumentRepository documents;
  @Inject SbomRegistryCollection sboms;

  @Override
  public String type() {
    return SbomProfile.KEY;
  }

  @Override
  public List<GcCandidate> enumerate() {
    List<GcCandidate> candidates = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (!SbomProfile.KEY.equals(repository.type)) {
        continue;
      }
      for (SbomDocument row : documents.<SbomDocument>list("repository = ?1", repository.name)) {
        candidates.add(
            new GcCandidate(
                row.repository,
                row.packageType + "/" + row.packageName + AT + row.version,
                // The belt counts per PACKAGE, not per repository: every release of every artifact
                // on the platform lands in the one `sboms` root, so a group of "the repository"
                // would let a busy package spend a quiet one's slots within days.
                row.repository + "/" + row.packageType + "/" + row.packageName,
                // A calver row is a release; anything else is a working artifact that lives on the
                // access window alone. See the class javadoc.
                CALVER.matcher(row.version).matches(),
                latest(row.createdAt, row.accessedAt),
                Set.of(row.blobId)));
      }
    }
    return List.copyOf(candidates);
  }

  /**
   * Oldest first: calver versions by their numeric parts, everything else below them by {@code
   * lastAccessAt}; ties on the identity so a report is stable across runs.
   *
   * <p>With {@code released = isCalver} the non-calver rungs are unreachable from the belt — the
   * engine sorts released candidates only — but the comparator must stay total and sane: if the
   * release meaning ever changes here, "oldest" must not quietly mean "smallest string". {@code
   * lastAccessAt} is the honest age the candidate actually carries for a non-calver version.
   */
  @Override
  public Comparator<GcCandidate> byAge() {
    return (left, right) -> {
      String leftVersion = versionOf(left.identity());
      String rightVersion = versionOf(right.identity());
      boolean leftCalver = CALVER.matcher(leftVersion).matches();
      boolean rightCalver = CALVER.matcher(rightVersion).matches();
      if (leftCalver != rightCalver) {
        return leftCalver ? 1 : -1; // every calver release ranks above (newer than) any other row
      }
      int compared =
          leftCalver
              ? BY_VERSION.compare(leftVersion, rightVersion)
              : left.lastAccessAt().compareTo(right.lastAccessAt());
      return compared != 0 ? compared : left.identity().compareTo(right.identity());
    };
  }

  @Override
  public GcStrategy.Applied delete(GcStrategy.Plan plan, GcStrategy.GraceWindow grace) {
    List<GcIdentity> deleted = new ArrayList<>();
    List<GcIdentity> withheld = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (GcIdentity dead : plan.dead()) {
      String packageType = packageTypeOf(dead.identity());
      String packageName = packageNameOf(dead.identity());
      String version = versionOf(dead.identity());
      try {
        SbomDocument row =
            documents.findOne(dead.repository(), packageType, packageName, version).orElse(null);
        if (row == null) {
          errors.add(dead.identity() + ": no such sbom row — the store moved since planning");
          continue;
        }
        // A document whose blob is still inside the grace window is withheld whole: deleting the row
        // over it would strand the blob as row-less and therefore untouchable forever, which is the
        // failure the window exists to prevent.
        if (grace.withinGrace(row.blobId)) {
          withheld.add(dead);
          continue;
        }
        sboms.collect(dead.repository(), packageType, packageName, version);
        deleted.add(dead);
      } catch (RuntimeException failed) {
        errors.add(dead.identity() + ": " + failed.getMessage());
      }
    }
    return new GcStrategy.Applied(deleted, withheld, errors);
  }

  /** The declared artifact type, up to the first {@code /} — {@code npm|maven|docker|daemon}. */
  private static String packageTypeOf(String identity) {
    return identity.substring(0, identity.indexOf('/'));
  }

  /**
   * The declared name, between the first {@code /} and the LAST {@code @}. It may itself contain
   * both — {@code @qits/ui-components} carries an {@code @} at its front and a {@code /} in its
   * middle — which is why neither delimiter is searched for from the side the name is on.
   */
  private static String packageNameOf(String identity) {
    return identity.substring(identity.indexOf('/') + 1, identity.lastIndexOf(AT));
  }

  /**
   * A package name may contain an {@code @} — {@code @qits/ui-components} begins with one — so the
   * <b>last</b> separates it from the version. A version cannot contain one: {@code SbomPaths}'
   * {@code VERSION} charset does not admit it.
   */
  private static String versionOf(String identity) {
    return identity.substring(identity.lastIndexOf(AT) + 1);
  }

  /** Publication counts as the first access, so a document stored minutes ago reads as young. */
  private static Instant latest(Instant created, Instant accessed) {
    return accessed == null || accessed.isBefore(created) ? created : accessed;
  }

  /**
   * Version order, oldest first: calver versions by their three numeric parts, anything else below
   * them with a lexical tie-break so the order stays total.
   *
   * <p>Deliberately <b>not</b> {@code DaemonBinariesGcAdapter.BY_VERSION}, which it otherwise
   * resembles. That comparator carries a third rung for the adopted rows whose version is the blob's
   * own digest hex — a shape only the daemon type has, from a one-off ops adoption. An SBOM's
   * version is whatever version the artifact it describes was released under, so borrowing it would
   * import a rank that can never fire and a javadoc that explains something untrue of this type.
   * Version order is a per-type fact, which is why {@link GcTypeAdapter#byAge} is on the adapter
   * rather than in an engine.
   */
  static final Comparator<String> BY_VERSION =
      (left, right) -> {
        boolean leftCalver = CALVER.matcher(left).matches();
        boolean rightCalver = CALVER.matcher(right).matches();
        if (leftCalver != rightCalver) {
          return leftCalver ? 1 : -1;
        }
        if (!leftCalver) {
          return left.compareTo(right);
        }
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        for (int part = 0; part < leftParts.length; part++) {
          int compared =
              Long.compare(Long.parseLong(leftParts[part]), Long.parseLong(rightParts[part]));
          if (compared != 0) {
            return compared;
          }
        }
        return 0;
      };
}
