package eu.wohlben.qits.artifacts.dto;

/**
 * One image in an {@code oci-images} repository.
 *
 * <p>There is no image table: an image exists exactly as long as a manifest names it, so both counts
 * are scans of {@code oci_manifest} / {@code oci_tag} and neither is stored anywhere.
 *
 * @param manifestCount every manifest ever pushed under this name, tagged or not. It exceeds {@link
 *     #tagCount} whenever a tag has been moved or a multi-arch index pushed its children.
 * @param sizeBytes the <b>union</b> of every blob this image's manifests reach, counted once. This
 *     is the honest headline: summing the per-tag figures below it inflates the answer by 2.6× on
 *     this store, because every rebuild shares its base layers with the tag before it.
 */
public record ImageSummary(String name, long tagCount, long manifestCount, long sizeBytes) {}
