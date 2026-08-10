package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.DocsRegistryCollection;
import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.DocsSite;
import eu.wohlben.qits.artifacts.control.DocsProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.DocsFileRepository;
import eu.wohlben.qits.artifacts.persistence.DocsSiteRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Published documentation sites, as facts: one <b>version</b> is one identity, every version is a
 * release, and nothing outside this service names one.
 *
 * <h2>A version is the identity, and a file is not</h2>
 *
 * <p>This is the fact that matters most about this type, and it is enforced two layers below here.
 * {@link #enumerate} emits one candidate per {@code docs_site} row and there is no query in this
 * class that could produce one per file; {@code DocsRegistryCollection} offers no method taking a
 * path; and V12's {@code on delete cascade} removes a version's files with it. So "collect half a
 * site" is not a plan this engine can produce, not a call this adapter can make, and not a state the
 * schema can hold — a site that still listed itself while answering 404 for its own stylesheet would
 * look like a broken deployment rather than a completed collection, and none of the three layers
 * will let it happen.
 *
 * <p>A candidate's blob set is every blob the version's files name. Those sets <b>overlap heavily
 * between versions</b> — fonts and unchanged chunks are byte-identical from one release to the next
 * — which is exactly what the retained-set union is for: a blob another surviving version also names
 * stays, because that version's candidate retains it. Nothing here has to reason about the sharing,
 * and nothing here should.
 *
 * <h2>Every row is a release</h2>
 *
 * <p>There is no prerelease coordinate on this type, {@code daemon_binaries}' situation for the same
 * reason: a {@code docs_site} row is written in the same transaction as a publish, publishes come
 * from a release pipeline, and versions are immutable. So {@link GcCandidate#released()} is true for
 * every row, and the belt reads as the settlement's sentence — the last releases of every site live
 * whatever their age, and the rest ages out once nobody has read it for the configured window.
 *
 * <h2>Nothing pins a docs version, and that is a decision</h2>
 *
 * <p>{@link #pinnedBy} is not overridden. qits-docs is a stateless view: it resolves {@code latest}
 * by asking for a site's newest version rather than holding a pointer, so there is no coordinate for
 * it to pin and no endpoint for this adapter to read. Declaring a pin source that answers "the
 * newest" would restate the belt in a second place and let the two disagree.
 *
 * <p>The <b>digest</b> floor still applies, as it does to every own type: {@code OwnGcStrategy}
 * checks pinned blob digests under whatever this adapter answers, and a docs bundle's files are
 * ordinary deduplicated blobs that something else may pin.
 *
 * <h2>Effective access</h2>
 *
 * <p>{@code max(published_at, accessed_at)} — V12's column, moved by any file of the version being
 * served and coalesced hourly, so one page load is one write rather than fifty. Reading a site is
 * what keeps it, which is the right basis here: documentation nobody has opened in a quarter is
 * documentation for a version nobody is on.
 */
@Singleton
public class DocsGcAdapter implements GcTypeAdapter {

  /** The separator between a site's name and its version, as the wire spells it. */
  static final String AT = "@";

  /** A calver release version: {@code <year>.<month><day>.<time>}. */
  static final Pattern CALVER = Pattern.compile("\\d{4}\\.\\d{1,4}\\.\\d+");

  @Inject ArtifactRepositoryRepository repositories;
  @Inject DocsSiteRepository sites;
  @Inject DocsFileRepository files;
  @Inject DocsRegistryCollection docs;

  @Override
  public String type() {
    return DocsProfile.KEY;
  }

  @Override
  public List<GcCandidate> enumerate() {
    List<GcCandidate> candidates = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (!DocsProfile.KEY.equals(repository.type)) {
        continue;
      }
      for (DocsSite row : sites.<DocsSite>list("repository = ?1", repository.name)) {
        candidates.add(
            new GcCandidate(
                row.repository,
                row.name + AT + row.version,
                // The belt counts per SITE, not per repository: two libraries releasing on the same
                // cadence must not spend each other's slots.
                row.repository + "/" + row.name,
                true,
                latest(row.publishedAt, row.accessedAt),
                Set.copyOf(files.listBlobIds(row.repository, row.name, row.version))));
      }
    }
    return List.copyOf(candidates);
  }

  /** Oldest first by version; ties on the identity so a report is stable across runs. */
  @Override
  public Comparator<GcCandidate> byAge() {
    return Comparator.comparing(
            (GcCandidate candidate) -> versionOf(candidate.identity()), BY_VERSION)
        .thenComparing(GcCandidate::identity);
  }

  @Override
  public GcStrategy.Applied delete(GcStrategy.Plan plan, GcStrategy.GraceWindow grace) {
    List<GcIdentity> deleted = new ArrayList<>();
    List<GcIdentity> withheld = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (GcIdentity dead : plan.dead()) {
      int at = dead.identity().lastIndexOf(AT);
      String name = dead.identity().substring(0, at);
      String version = dead.identity().substring(at + 1);
      try {
        DocsSite row = sites.findOne(dead.repository(), name, version).orElse(null);
        if (row == null) {
          errors.add(dead.identity() + ": no such docs row — the store moved since planning");
          continue;
        }
        // Any blob of the bundle still inside the grace window withholds the WHOLE version, because
        // the version is what would be deleted: collecting it would strand that blob as row-less and
        // therefore untouchable forever, which is the failure the window exists to prevent.
        if (files.listBlobIds(dead.repository(), name, version).stream()
            .anyMatch(grace::withinGrace)) {
          withheld.add(dead);
          continue;
        }
        docs.collect(dead.repository(), name, version);
        deleted.add(dead);
      } catch (RuntimeException failed) {
        errors.add(dead.identity() + ": " + failed.getMessage());
      }
    }
    return new GcStrategy.Applied(deleted, withheld, errors);
  }

  /**
   * A site name may contain an {@code @} — {@code @qits/ui-components} begins with one — so the
   * <b>last</b> separates it from the version. A version cannot contain one: {@code DocsPaths}'
   * {@code VERSION} class does not admit it.
   */
  private static String versionOf(String identity) {
    return identity.substring(identity.lastIndexOf(AT) + 1);
  }

  /** Publication counts as the first access, so a version published minutes ago reads as young. */
  private static Instant latest(Instant published, Instant accessed) {
    return accessed == null || accessed.isBefore(published) ? published : accessed;
  }

  /**
   * Version order, oldest first: calver versions by their three numeric parts, anything else below
   * them with a lexical tie-break so the order stays total.
   *
   * <p>Deliberately <b>not</b> {@code DaemonBinariesGcAdapter.BY_VERSION}, which it otherwise
   * resembles. That comparator carries a third rung for the adopted rows whose version is the blob's
   * own digest hex — a shape only the daemon type has, from a one-off ops adoption. Docs versions
   * come from the release train and nowhere else, so borrowing it would import a rank that can never
   * fire and a javadoc that explains something untrue of this type. Version order is a per-type fact,
   * which is why {@link GcTypeAdapter#byAge} is on the adapter rather than in an engine.
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
