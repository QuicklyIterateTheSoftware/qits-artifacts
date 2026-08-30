package eu.wohlben.qits.artifacts.dto;

import eu.wohlben.qits.blobstore.dto.ArtifactRepositoryDto;
import java.time.Instant;

/**
 * A repository as the explorer's top level shows it: what it is, how much is in it, and what it
 * costs.
 *
 * <p>Separate from {@link ArtifactRepositoryDto}, which is what the ensure endpoint echoes back — a
 * write response has no business counting anything, and a count that is always zero there would read
 * as a fact.
 *
 * @param type the <b>kebab wire form</b> ({@code oci-images}), not the stored key — the entity
 *     carries the stored one and the caller converts, the same split {@link ArtifactRepositoryDto}
 *     makes.
 * @param itemCount what "one thing" means for this type: images for {@code oci-images}, packages for
 *     {@code npm-packages}, records for the two CI types. Deliberately one number with a
 *     type-dependent meaning rather than four always-null ones.
 * @param sizeBytes the repository's referenced-blob <b>union</b>, never a sum over its rows. Null
 *     only if it cannot be established at all.
 */
public record RepositorySummary(
    String name, String type, Instant createdAt, long itemCount, Long sizeBytes) {}
