package eu.wohlben.qits.artifacts.dto;

import java.time.Instant;

/**
 * One documentation site in a {@code docs} repository, folded from its published versions.
 *
 * <p>The same four columns the open wire catalog answers ({@code GET /artifacts/docs/<repo>}) plus
 * the one it deliberately does not: a size. The catalog is what qits-platform-docs renders and it
 * says what exists; this is the operator's view and it says what that costs.
 *
 * <p><b>{@link #sizeBytes} is the union over the site's distinct blob ids, never the sum of its
 * versions.</b> Docs versions share blobs by design and heavily — fonts and unchanged chunks are
 * byte-identical across releases and are stored once — so adding the versions' published totals
 * overstates a Storybook-shaped site several times over. {@code DocsSite.totalBytes} is that sum and
 * is deliberately not what this reports: it sizes the bundle as published, not the disk.
 *
 * @param latestVersion the newest published version by {@code published_at}; never null, because a
 *     site with no versions is not enumerated at all
 * @param latestPublishedAt when that newest version was published — server-stamped, so never null
 */
public record DocsSiteSummary(
    String name,
    long versionCount,
    String latestVersion,
    Instant latestPublishedAt,
    long sizeBytes) {}
