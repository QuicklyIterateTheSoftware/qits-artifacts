package eu.wohlben.qits.artifacts.gc.dto;

/**
 * One thing a garbage collection plan would delete, or would keep, and why.
 *
 * <p>An identity is what a per-type strategy acts on — a tag, a version, a record row — never a
 * blob. Blobs have no meaning of their own and are reconciled separately.
 *
 * @param repository the {@code artifact_repository} row it lives in
 * @param identity the type's own coordinate for it: {@code alpha:2026.801.85448} for a tag, {@code
 *     @qits/ui-components@1.2.3-main.gab854a1} for a version — spelled the way the type's own tools
 *     spell it, so a reviewer can look it up without translating
 * @param rule the named rule that condemned or saved it. A dry-run report exists to be argued with,
 *     and a list of doomed coordinates with no rule beside them cannot be.
 */
public record GcIdentity(String repository, String identity, String rule) {}
