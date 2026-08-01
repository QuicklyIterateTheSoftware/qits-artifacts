package eu.wohlben.qits.artifacts.dto;

/**
 * One npm package, in a hosted repository or a proxy one.
 *
 * @param latest the version the {@code latest} dist-tag names, or null where there is none — which
 *     is <b>every</b> proxied package: a proxy caches tarballs and packument documents, and stores
 *     no dist-tag rows of its own.
 */
public record PackageSummary(String name, long versionCount, String latest) {}
