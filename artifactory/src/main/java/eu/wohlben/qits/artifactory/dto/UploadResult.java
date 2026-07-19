package eu.wohlben.qits.artifactory.dto;

/**
 * The outcome of an upload: {@code id} is the content id (the blob's SHA-256), {@code existing} is
 * whether those exact bytes were already stored (dedupe — a new metadata record is created either
 * way).
 */
public record UploadResult(String id, boolean existing) {}
