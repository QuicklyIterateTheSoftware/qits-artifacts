package eu.wohlben.qits.artifacts.dto;

import java.time.Instant;
import java.util.List;

/**
 * One version of one npm package.
 *
 * @param tarballSizeBytes the tarball's size <b>on disk</b>. There is no size column — {@code
 *     npm_version} carries the blob's id and npm's two hashes and nothing else — so this is null
 *     when the file is not there, which is a real state rather than a defensive one: a row can
 *     outlive its bytes and the honest answer is "unknown", not zero.
 * @param publishedAt when this row was written. For a proxied version that is when the tarball was
 *     first pulled through, not when upstream published it.
 * @param accessedAt when a tarball GET last served this version, coarsened to once an hour. Null is
 *     an explicit state — never read since tracking began (V11) — and not "unknown", the same
 *     meaning the field carries on {@code ImageTagSummary} and {@code ArtifactRecordDto}.
 * @param distTags every dist-tag naming this version, lexically. Empty for a proxied version, which
 *     has none.
 */
public record PackageVersionSummary(
    String version,
    Long tarballSizeBytes,
    Instant publishedAt,
    Instant accessedAt,
    List<String> distTags) {}
