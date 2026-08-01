package eu.wohlben.qits.artifacts.dto;

import eu.wohlben.qits.artifacts.entity.RepositoryType;
import java.time.Instant;

/**
 * A repository as the explorer's top level shows it: what it is, how much is in it, and what it
 * costs.
 *
 * <p>Separate from {@link ArtifactRepositoryDto}, which is what the ensure endpoint echoes back — a
 * write response has no business counting anything, and a count that is always zero there would read
 * as a fact.
 *
 * @param itemCount what "one thing" means for this type: images for {@code oci-images}, packages for
 *     either npm type, records for the two CI types. Deliberately one number with a
 *     type-dependent meaning rather than four always-null ones.
 * @param sizeBytes the repository's referenced-blob <b>union</b>, never a sum over its rows. Null
 *     only if it cannot be established at all.
 */
public record RepositorySummary(
    String name, RepositoryType type, Instant createdAt, long itemCount, Long sizeBytes) {}
