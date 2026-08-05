-- Coarse last-access timestamps for the three protocol tables V9 could not reach: npm versions,
-- maven files and daemon binaries. V9 gave the column to artifact_record, oci_manifest and oci_tag;
-- the settled garbage collection reads BOTH of its strategies off this column (artifacts-gc-plan.md,
-- "Settlement": caches delete everything unaccessed for the window, own types keep the last released
-- versions and delete the unaccessed rest), so a protocol table without it is a type the sweep
-- cannot reason about at all.
--
-- Same shape as V9, deliberately: nullable, written by application code at most once per row per
-- hour (ArtifactAccessTracker), never by a trigger. And blobs stay untracked for V9's reason — they
-- dedupe globally, so a read of one cannot be attributed to any repository that references it.
--
-- NO BACKFILL, which is V9's decision restated rather than a new one: null means "never read since
-- tracking began", and that is exactly what a sweep must be able to tell apart from "read long ago".
-- Stamping created_at/published_at into the column instead would date every existing row as freshly
-- accessed and hide it from the window the sweep computes — the one direction of error that keeps
-- garbage alive silently. A null row ages in on its first read.
--
-- THE NUMBER. No plan reserves a migration number (V7's and V10's headers are the rule): each
-- workstream takes the next free V at land time. The lineage ended at V10 when this landed. Nothing
-- here adds a RepositoryType constant, so ck_artifact_repository_type is NOT re-enumerated — the
-- rule is "re-enumerate when you widen it", not "restate it in every migration".
alter table npm_version add column accessed_at timestamp(6) with time zone;
alter table maven_artifact add column accessed_at timestamp(6) with time zone;
alter table daemon_binary add column accessed_at timestamp(6) with time zone;

-- Each index is its table's identity prefix plus the column, the shape V9 chose: a sweep asks "what
-- in this repository (this package, this daemon) has not been read since X", which is a range scan
-- on the leading columns it already keys by.
create index idx_npm_version_package_accessed
    on npm_version (repository, package_name, accessed_at);
create index idx_maven_artifact_repository_accessed on maven_artifact (repository, accessed_at);
create index idx_daemon_binary_name_accessed on daemon_binary (repository, name, accessed_at);
