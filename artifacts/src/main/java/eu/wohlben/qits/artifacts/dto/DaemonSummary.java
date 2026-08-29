package eu.wohlben.qits.artifacts.dto;

import java.time.Instant;

/**
 * One platform daemon in the {@code daemons} repository, folded from its published versions.
 *
 * <p>There is no daemon table: a daemon exists exactly as long as a {@code daemon_binary} row names
 * it, so {@link #name} is a distinct scan and {@link #versionCount} is a count over that name — the
 * same shape {@code ImageSummary} has over {@code oci_manifest}, and for the same reason.
 *
 * <p><b>{@link #sizeBytes} is the union over that daemon's distinct blob ids, never the sum of its
 * versions.</b> Two versions built from identical bytes — a rebuild that changed nothing, or a
 * calver re-tag of an adopted digest row — are one blob in the store, and adding their row sizes
 * would report disk this daemon does not occupy.
 *
 * @param latestVersion the newest published version by {@code published_at}; never null, because a
 *     daemon with no versions is not enumerated at all
 * @param latestPublishedAt when that newest version was published — server-stamped, so never null
 */
public record DaemonSummary(
    String name,
    long versionCount,
    String latestVersion,
    Instant latestPublishedAt,
    long sizeBytes) {}
