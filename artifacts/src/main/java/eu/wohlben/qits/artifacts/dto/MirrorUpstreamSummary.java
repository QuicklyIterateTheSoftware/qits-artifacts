package eu.wohlben.qits.artifacts.dto;

import java.time.Instant;

/**
 * A registered upstream as the API shows it: which registry, under which namespace, and how much of
 * it has actually been pulled through.
 *
 * @param domain the upstream registry's domain — its identity, and what the miss path dials
 * @param slug the local namespace segment: {@code docker pull <host>/<slug>/<image>:<tag>}
 * @param cachedImages distinct image names cached under the namespace. Zero is the normal state of a
 *     fresh upstream and says so — a mirror holds only what somebody pulled.
 */
public record MirrorUpstreamSummary(
    String domain, String slug, Instant createdAt, long cachedImages) {}
