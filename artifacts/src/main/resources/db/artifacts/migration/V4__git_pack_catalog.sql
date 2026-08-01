-- The git host's pack catalog: which packs a repository has, and which blob holds each of their
-- files. Same lineage and same named `artifacts` datasource as V1/V2/V3, which is NOT a squash
-- baseline.
--
-- WHY THIS LINEAGE. Until now "the git host owns no tables at all" was true, and CLAUDE.md said so.
-- The second storage engine (git-storage, a JGit DfsRepository whose packs and refs are blobs)
-- needs a list of packs per repository, and that list is the only mutable state such a repository
-- has. The alternative was a second named datasource with a second H2 file and a second Flyway
-- config; this is one datasource and one migration path, and the invariant is amended in writing
-- rather than left standing and false. Nothing here is a foreign key into anything: `repository_id`
-- is an opaque string, exactly as the blob store addresses the world by string metadata.
--
-- WE DO NOT GARBAGE COLLECT GIT, and this is where the next person reads it.
--   The blob store has no delete. So DfsGarbageCollector does not reclaim, it DUPLICATES: the
--   repacked pack is written, the packs it replaced are dropped from the tables below, and their
--   bytes stay forever. Measured on the platform's largest real repository: 22 packs and 7.8 MB
--   became 2 packs and 15 MB. ONE RUN NEARLY DOUBLED THE FOOTPRINT.
--   The accepted cost instead is roughly three blobs and three rows per push — about 75 blobs per
--   active repository per year at the measured rate, against a blob store already measured in
--   gigabytes. Nobody schedules a repack here to save space.

-- One pack of one repository.
--
-- `pack_name` is a UUID and is NEVER reused: (repository_id, pack_name) is the key for all time, a
-- row is never updated in place, and a name collision would not fail — JGit compares descriptions
-- by name, so it would quietly serve the wrong bytes. That is why the name is not a counter, which
-- would restart at 1 after a redeploy.
--
-- Three columns must round-trip EXACTLY or refs and objects read wrong after a restart, and none of
-- them fails visibly:
--   last_modified                   sorting key for object lookup and for the reftable stack
--   min_update_index/max_update_index   the reftable stack's primary ordering
--
-- `source` is JGit's PackSource BY NAME rather than an enum or a check constraint, so a JGit
-- version that adds one needs no migration; an unrecognised value is read back as
-- UNREACHABLE_GARBAGE rather than failing the whole repository.
create table git_pack (
    repository_id varchar(255) not null,
    pack_name varchar(128) not null,
    source varchar(64) not null,
    last_modified bigint not null,
    object_count bigint not null,
    delta_count bigint not null,
    min_update_index bigint not null,
    max_update_index bigint not null,
    index_version integer not null,
    primary key (repository_id, pack_name)
);

-- One file of one pack, and the blob that holds it: `pack`, `idx`, `reftable`, and whatever a later
-- JGit adds. The extension is a STRING for the same reason `source` is — a catalog row must not
-- carry a JGit enum, or an upgrade that renames one silently changes what stored rows mean.
--
-- `blob_id` is an ordinary BlobStore content address (sha256 hex), keyed like every other byte in
-- this service. Dropping the pack row above frees nothing here and nothing on disk; see the GC note.
create table git_pack_file (
    repository_id varchar(255) not null,
    pack_name varchar(128) not null,
    extension varchar(32) not null,
    blob_id varchar(64) not null,
    file_size bigint not null,
    block_size integer not null,
    primary key (repository_id, pack_name, extension)
);

-- Opening a repository lists every pack it has, and closing a receive-pack deletes the files of the
-- packs it replaced. Both walk one repository, so both want this index.
create index idx_git_pack_file_pack on git_pack_file (repository_id, pack_name);
