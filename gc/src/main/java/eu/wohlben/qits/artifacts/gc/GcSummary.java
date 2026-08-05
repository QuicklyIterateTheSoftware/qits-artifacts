package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.gc.dto.GcPlanSummary;
import eu.wohlben.qits.artifacts.gc.dto.GcSweepPlan;
import eu.wohlben.qits.artifacts.gc.dto.GcTypeConfiguration;
import eu.wohlben.qits.artifacts.gc.dto.GcTypePlan;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The plan, read back as the paragraph a human starts with.
 *
 * <p>Nothing here is computed: every figure is taken from the report it summarises, so the summary
 * can be wrong about the plan only by being wrong about arithmetic. That is deliberate — a summary
 * that re-derived "what would die" would be a second policy, and two policies in one report is the
 * mistake the whole design refuses.
 *
 * <p>It exists because the plan is long. Eight types, each with two identity lists, a configuration
 * echo and a pins section is the right amount of detail to <em>check</em> a decision and the wrong
 * amount to <em>take</em> one: a reviewer needs "can this run, what does it cost, and which type is
 * doing the work" before any of it means anything.
 */
final class GcSummary {

  private GcSummary() {}

  /** The headline, the totals, and one line per type. */
  static GcPlanSummary of(
      List<GcTypeConfiguration> configuration,
      List<GcTypePlan> types,
      GcSweepPlan sweep,
      GcPins pins,
      String graceWindow) {
    Map<RepositoryType, GcTypeConfiguration> configured = new EnumMap<>(RepositoryType.class);
    for (GcTypeConfiguration line : configuration) {
      configured.put(line.type(), line);
    }

    int condemned = 0;
    List<String> lines = new ArrayList<>();
    for (GcTypePlan type : types) {
      condemned += type.dead().size();
      lines.add(line(type, configured.get(type.type())));
    }

    return new GcPlanSummary(
        pins.complete(),
        headline(pins, condemned, sweep, graceWindow),
        condemned,
        sweep.blobCount(),
        sweep.reclaimableBytes(),
        bytes(sweep.reclaimableBytes()),
        sweep.withheldByGraceWindow(),
        List.copyOf(lines));
  }

  /**
   * The whole plan in one sentence — including, first, the reason it cannot be executed.
   *
   * <p>A non-executable plan's figures are still worth reading (they are what the types that could
   * plan would do), and are the one thing a reader must not mistake for tonight's outcome. So the
   * refusal leads and the figures follow it, rather than the other way round.
   */
  private static String headline(
      GcPins pins, int condemned, GcSweepPlan sweep, String graceWindow) {
    String withheld =
        sweep.withheldByGraceWindow() == 0
            ? ""
            : ", with "
                + sweep.withheldByGraceWindow()
                + " blobs ("
                + bytes(sweep.withheldBytes())
                + ") withheld by the "
                + graceWindow
                + " grace window";
    String work =
        condemned == 0 && sweep.blobCount() == 0
            ? "no type condemned an identity, so nothing would be deleted and no disk would come"
                + " back"
            : condemned
                + " identities would be deleted and "
                + sweep.blobCount()
                + " blob files unlinked, reclaiming "
                + bytes(sweep.reclaimableBytes())
                + withheld;
    if (!pins.complete()) {
      return "NOT EXECUTABLE: a sweep run now would abort before the census and delete nothing — "
          + pins.whyIncomplete()
          + ". The figures below are what the types that could still plan would do, not what a run"
          + " tonight would do ("
          + work
          + ").";
    }
    return "a sweep run now would execute this plan: " + work + ".";
  }

  /**
   * One type in one line: its configured engine and window, what it would do, and its own caption.
   *
   * <p>The configuration is on the same line as the outcome on purpose. "Nothing died" reads
   * identically whether the rule is right or the window is a year, and a summary that showed only
   * outcomes would reproduce exactly the unreviewable report the configuration echo was added to
   * prevent.
   */
  private static String line(GcTypePlan type, GcTypeConfiguration configured) {
    StringBuilder line = new StringBuilder(type.type().wireName());
    if (configured != null && configured.strategy() != null) {
      line.append(" (").append(configured.strategy());
      if (configured.window() != null) {
        line.append(", ").append(configured.window());
      }
      line.append(")");
    }
    line.append(": ").append(outcome(type, configured));
    String note = caption(type);
    if (note != null) {
      line.append(" — ").append(firstSentence(note));
    }
    return line.toString();
  }

  private static String outcome(GcTypePlan type, GcTypeConfiguration configured) {
    // The configuration leads, whatever the registration says: a type nobody collects because
    // nobody configured an engine for it is a decision, and reporting it as a missing strategy
    // would send a reader looking for the class that is deliberately not there.
    if (configured != null && GcRules.EXCLUDED_STRATEGY.equals(configured.strategy())) {
      return "excluded by configuration, so nothing of it is ever deleted — a decision, not a gap";
    }
    if (type.strategy() == null) {
      return "no strategy registered, so nothing of it is ever collected";
    }
    if (type.error() != null) {
      // The headline already carries the pin failures in full, and six types repeating them is a
      // summary nobody reads to the end. The reason in full stays on the type's own entry.
      return "refused: " + upTo(type.error(), " — ");
    }
    return type.dead().size()
        + " identities condemned, "
        + type.kept().size()
        + " kept, "
        + type.blobsSweepable()
        + " blobs freed, "
        + bytes(type.reclaimableBytes())
        + " reclaimable";
  }

  /** The type's own note, with the excluded prefix removed when the line already carries it. */
  private static String caption(GcTypePlan type) {
    String note = type.note();
    if (note == null || type.strategy() == null) {
      return null;
    }
    return note.startsWith(GcRules.EXCLUDED_NOTE)
        ? note.substring(GcRules.EXCLUDED_NOTE.length())
        : note;
  }

  /**
   * Enough of a note for a summary line. The notes that matter here lead with their point — the npm
   * proxy's H2 line opens with the fact that a packument is a CLOB and frees no disk — so the first
   * sentence is the honest short form and the full text stays on the type's own entry.
   */
  private static String firstSentence(String note) {
    int stop = note.indexOf(". ");
    return stop < 0 ? note : note.substring(0, stop + 1);
  }

  /** The text before a separator, or all of it when the separator is not there. */
  private static String upTo(String text, String separator) {
    int stop = text.indexOf(separator);
    return stop < 0 ? text : text.substring(0, stop);
  }

  /**
   * Bytes a human reads. The exact figure is beside it in the report; this one exists so nobody
   * counts digits to find out whether a plan reclaims 40 MB or 400.
   */
  static String bytes(long value) {
    if (value < 1024) {
      return value + " B";
    }
    String[] units = {"KiB", "MiB", "GiB", "TiB"};
    double scaled = value;
    int unit = -1;
    while (scaled >= 1024 && unit < units.length - 1) {
      scaled /= 1024;
      unit++;
    }
    return String.format(Locale.ROOT, "%.1f %s", scaled, units[unit]);
  }
}
