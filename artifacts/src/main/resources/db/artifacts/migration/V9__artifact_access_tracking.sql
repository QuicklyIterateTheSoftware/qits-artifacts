-- Coarse last-access timestamps for cleanup decisions. Access writes are coalesced in application
-- code to at most once per row per hour. OCI blobs deliberately remain rowless and untracked:
-- manifests/tags are repository-scoped reachability roots; blobs are globally deduplicated.
alter table artifact_record add column accessed_at timestamp(6) with time zone;
alter table oci_manifest add column accessed_at timestamp(6) with time zone;
alter table oci_tag add column accessed_at timestamp(6) with time zone;

create index idx_artifact_record_repository_accessed on artifact_record (repository, accessed_at);
create index idx_oci_manifest_image_accessed
    on oci_manifest (repository, image_name, accessed_at);
create index idx_oci_tag_image_accessed on oci_tag (repository, image_name, accessed_at);
