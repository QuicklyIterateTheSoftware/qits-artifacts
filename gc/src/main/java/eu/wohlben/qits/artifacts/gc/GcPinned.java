package eu.wohlben.qits.artifacts.gc;

/**
 * Whether something outside this service is holding on to an identity, and under which named rule.
 *
 * <p>Pinned is a <b>keep-class both engines check before the access rule</b>: a pin says "a live
 * service will pull this again", which no access timestamp can know — the deployment that pinned an
 * image sha may have been running untouched for months, and that is precisely when deleting it
 * breaks a restart.
 *
 * <p>The rule comes back as a sentence rather than a boolean so the report can name the pin that
 * saved an identity ("pinned by qits-cd deployment", "pinned by qits-ci daemon ladder"). A keep with
 * no rule beside it is a line nobody can argue with, which is the property the whole dry-run rests
 * on.
 *
 * <p>{@link #NONE} is the honest answer for a type nothing pins, and the shipped answer while the
 * engines are dark.
 */
@FunctionalInterface
public interface GcPinned {

  /** Nothing pins anything — the answer for a type no live service holds a reference into. */
  GcPinned NONE = candidate -> null;

  /** The named rule pinning this identity, or null when nothing does. */
  String pinnedBy(GcCandidate candidate);
}
