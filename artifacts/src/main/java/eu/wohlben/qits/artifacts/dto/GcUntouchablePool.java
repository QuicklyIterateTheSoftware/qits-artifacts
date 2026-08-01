package eu.wohlben.qits.artifacts.dto;

import java.util.List;

/**
 * Blobs on disk that no identity row of any type names — reported, never swept.
 *
 * <p>They look exactly like garbage and are the one thing this mechanism must never touch. The
 * store's pool is 124 MiB in three ELF binaries pushed through the OCI blob-upload session with no
 * manifest, and one of them is the CI daemon binary every build downloads by digest: a sweep that
 * deleted "everything referenced by no row" would stop CI platform-wide. The rule that prevents it
 * is structural rather than an allowlist — a blob may only become a candidate by <b>losing</b> its
 * last identity row to a strategy's own deletion, so one that never had a row is out of reach.
 *
 * <p>The git host's DFS pack blobs sit in this pool too, until git's own GC contributes them as a
 * live set. That is why they are safe today and why nothing about them needs a gate.
 *
 * @param reason the rule, restated in the response, because this list is the proof a reviewer reads
 * @param blobCount how many files
 * @param bytes what they occupy on disk
 * @param blobIds every digest, sorted — the ci-daemon binary must be findable in this list
 */
public record GcUntouchablePool(String reason, int blobCount, long bytes, List<String> blobIds) {}
