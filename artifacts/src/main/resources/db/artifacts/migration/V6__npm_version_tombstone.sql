-- The npm republish tombstone: a version's identity, kept after its row is gone.
--
-- WHY IT EXISTS. Version immutability is enforced by looking for the row (`NpmRegistryService
-- .publish`): a version that has a row is 403, a version that has none is a fresh publish. Garbage
-- collection deletes rows. Without this table the first GC of a prerelease would quietly re-open
-- its name for a publish carrying DIFFERENT bytes, and a name that resolved to two different
-- tarballs over its lifetime is exactly the mutability this registry refuses. Immutability's
-- meaning narrows honestly from "a version row is never touched" to "a version, once published, is
-- never republished", and this row is what carries the second half after the first stops being
-- true.
--
-- WHY A TABLE RATHER THAN A FLAG ON npm_version. A marker column would leave the row in place, and
-- the packument is assembled from those rows at read time (`NpmRegistryService.listVersions`), so
-- every reader would have to remember to filter — a `where` clause whose omission is a collected
-- version reappearing in a document. A separate table cannot be forgotten by a reader that does not
-- know about it.
--
-- WHAT IT DELIBERATELY DOES NOT HOLD. No manifest, no integrity, no shasum: this is an identity,
-- not an archive, and nothing may ever be served from it. `tarball_blob_id` is kept only so a
-- collected version can be traced back to the bytes a later sweep reclaimed; it is nullable because
-- the identity is the point and the blob id is provenance.
--
-- This table is npm's alone. Docker needs nothing like it: an OCI tag is a movable pointer by
-- design and re-pushing one has always been legal, so there is no immutability promise for a
-- deletion to weaken there.
--
-- Nothing writes a row yet. `NpmRegistryService.collect` is the only way one is ever written, and
-- it is package-private and called by nobody: garbage collection is dry-run, and the tombstone
-- ships first so the sweep can never run without it.

create table npm_version_tombstone (
    repository varchar(255) not null,
    package_name varchar(255) not null,
    version varchar(128) not null,
    tarball_blob_id varchar(64),
    collected_at timestamp(6) with time zone not null,
    primary key (repository, package_name, version)
);

-- Same-context foreign key, exactly like V3's three. (The "never a foreign key" rule in AGENTS.md
-- is about OTHER contexts' tables; artifact_repository is ours.)
alter table if exists npm_version_tombstone
    add constraint fk_npm_version_tombstone_repository foreign key (repository) references artifact_repository (name);
