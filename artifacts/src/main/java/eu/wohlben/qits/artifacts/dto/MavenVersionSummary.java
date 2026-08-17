package eu.wohlben.qits.artifacts.dto;

import java.time.Instant;
import java.util.List;

/** One Maven version and the concrete repository files that make it available. */
public record MavenVersionSummary(
    String version, List<String> files, long sizeBytes, Instant publishedAt) {}

