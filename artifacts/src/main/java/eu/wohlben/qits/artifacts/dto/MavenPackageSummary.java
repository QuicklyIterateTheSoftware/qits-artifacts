package eu.wohlben.qits.artifacts.dto;

/** One Maven coordinate available from a hosted repository. */
public record MavenPackageSummary(String name, long versionCount, long sizeBytes) {}

