package eu.wohlben.qits.artifacts.dto;

import java.time.Instant;
import java.util.Map;

/**
 * One published version of one documentation site — the unit of eviction, as an operator reads it.
 *
 * <p><b>{@link #sizeBytes} is the union over this version's distinct blob ids, never the sum of its
 * file rows.</b> A bundle may ship the same bytes at two paths, and adding {@code docs_file
 * .size_bytes} would count them twice; {@code DocsSite.totalBytes} is precisely that sum and is
 * deliberately not what this reports. The same discipline one level up dedupes the font every
 * version of the site carries — see {@link DocsSiteSummary#sizeBytes}.
 *
 * <p>{@link #fileCount} stays the row count rather than a distinct-blob count: it answers "how many
 * paths does this version serve", which is a fact about the site's shape, not about the disk.
 *
 * @param accessedAt when any file of this version was last served, coalesced to once an hour;
 *     <b>null means never read since tracking began</b>, which is a different fact from "read long
 *     ago" and is why this is nullable rather than zero-valued
 * @param metadata the flat string map the publisher rode on {@code X-Artifacts-Meta-*} headers —
 *     {@code git.branch.name}, {@code git.commit.hash} and whatever else it chose. Never null; a
 *     version published without any is an empty map, because "no metadata" and "metadata not read
 *     here" must not look the same on this surface.
 */
public record DocsVersionSummary(
    String version,
    int fileCount,
    long sizeBytes,
    Instant publishedAt,
    Instant accessedAt,
    Map<String, String> metadata) {}
