package eu.wohlben.qits.artifacts.dto;

import java.time.Instant;
import java.util.List;

/** One repository-scoped OCI manifest, including untagged reachability roots. */
public record ImageManifestSummary(
    String digest,
    String mediaType,
    long sizeBytes,
    Instant createdAt,
    Instant accessedAt,
    List<String> tags) {}
