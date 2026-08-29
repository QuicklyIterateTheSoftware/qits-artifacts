package eu.wohlben.qits.artifacts.dto;

import java.time.Instant;

/**
 * One published version of one daemon — the row, as an operator reads it.
 *
 * <p><b>{@link #digest} is the wire spelling {@code sha256:<hex>}, not the stored hex.</b> That is
 * the exact string {@code DaemonRoutes} echoes as {@code Docker-Content-Digest} on the download and
 * returns in the publish receipt, and it is what a deployment pins — so the browse surface must
 * spell it the same way or an operator copying it out of this listing pins a value the wire never
 * uttered. {@code ImageTagSummary.digest} is the same decision on the OCI half.
 *
 * <p>{@link #sizeBytes} is the row's, not a union: a daemon version <em>is</em> one blob, so there
 * is nothing here to double-count. The union only does work one level up, in {@link
 * DaemonSummary#sizeBytes}, where two versions can name the same bytes.
 *
 * @param accessedAt when the version-addressed GET last served these bytes, coalesced to once an
 *     hour; <b>null means never read since tracking began</b>, which is a different fact from "read
 *     long ago" and is why this is nullable rather than zero-valued. The digest-addressed {@code /v2}
 *     blob route deliberately does not move it.
 */
public record DaemonVersionSummary(
    String version, String digest, long sizeBytes, Instant publishedAt, Instant accessedAt) {}
