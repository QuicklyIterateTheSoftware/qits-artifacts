package eu.wohlben.qits.artifacts.dto;

import java.time.Instant;

/**
 * One tag of one image.
 *
 * @param digest the wire form, {@code sha256:<hex>} — what a client pastes into a pull by digest,
 *     not the bare hex the database stores
 * @param sizeBytes what this manifest references, counted once. <b>Not additive</b>: two tags of one
 *     image share nearly every layer, so adding these is the 2.6× overcount the image-level union
 *     exists to avoid. A view showing them must say so.
 * @param createdAt when this tag last came to name this digest ({@code oci_tag.updated_at}) — a tag
 *     is the registry's one movable pointer, so it has no other timestamp. For a tag that has never
 *     moved, which is every commit-sha tag this platform pushes, it is when the image was pushed.
 */
public record ImageTagSummary(
    String tag, String digest, long sizeBytes, Instant createdAt, Instant accessedAt) {}
