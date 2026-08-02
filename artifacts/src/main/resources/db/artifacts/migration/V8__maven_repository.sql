-- The maven repository type: a third protocol registry on the npm shape — one path-keyed table —
-- plus a widened type check for it. Same lineage and same named `artifacts` datasource as V1/V2,
-- which is NOT a squash baseline.
--
-- THE NUMBER. No plan reserves a migration number (V7's own header is the rule): each workstream
-- takes the next free V at land time and re-enumerates the check constraint from the RepositoryType
-- enum as it stands in the tree. This one landed after V7 (the OCI mirror), so the list below is
-- the enum's seven constants. OciMirrorMigrationTest loops values() over this real directory, so a
-- constant added without its widening fails the suite, not the deployment.

-- V2 re-added the type check NAMED precisely so this stays a one-liner. `if exists` keeps it
-- re-runnable against a database where it has already gone.
alter table artifact_repository drop constraint if exists ck_artifact_repository_type;

alter table artifact_repository
    add constraint ck_artifact_repository_type
    check (type in ('CI_SCREENSHOTS','CI_VIDEOS','OCI_IMAGES','NPM_PACKAGES','NPM_PROXY','OCI_MIRROR','MAVEN_PACKAGES'));

-- One deployed file of one maven repository: the path IS the identity.
--
-- The wire (eu.wohlben.qits.maven) is a dumb path store on the maven layout: `mvn deploy` PUTs the
-- jar, the pom and each file's checksums one request at a time, and the path under the repository
-- root is the only lookup any of it needs. GET resolves one row, PUT inserts one. Both of maven's
-- derived documents stay OUT of this table, the packument precedent at two levels:
-- maven-metadata.xml is assembled per request from these rows and never stored, so it cannot become
-- a second source of truth (a client's own metadata PUT is accepted and discarded); checksums are
-- derived at GET and verified at PUT, never stored either.
--
-- The BYTES are an ordinary BlobStore blob keyed by sha256 — blob_id is that key — so jars dedupe
-- globally with image layers and npm tarballs. size_bytes rides beside it: free at stage time, and
-- it makes this the one protocol table the census sizes without a disk read.
--
-- Release paths are immutable: a re-PUT of identical bytes is an idempotent no-op (deploy retries
-- are normal), a re-PUT of different bytes is 403. Timestamped snapshot files are unique by
-- construction and take the same rule; a literal -SNAPSHOT filename is the one mutable path
-- (maven-repository-plan.md §3.6).
create table maven_artifact (
    repository varchar(255) not null,
    path varchar(1024) not null,          -- the full maven-layout path, relative to the repo root
    blob_id varchar(64) not null,         -- sha256 of the bytes; the BlobStore key
    size_bytes bigint not null,
    created_at timestamp(6) with time zone not null,
    primary key (repository, path)
);

-- Metadata derivation prefix-scans `path like '<ga>/%'`: the primary key's leading columns are
-- that index, which is the right price at this store's scale (dozens of artifacts, not Central's
-- millions).

-- Same-context foreign key, exactly like V3's three and V7's one. (The "never a foreign key" rule
-- in AGENTS.md is about OTHER contexts' tables; artifact_repository is ours.)
alter table if exists maven_artifact
    add constraint fk_maven_artifact_repository foreign key (repository) references artifact_repository (name);

-- NO PREFILL: the `maven` repository row comes from the startup seeder, matching how `npm`, `npmjs`
-- and `qits` arrived — migrations prefill only what is static platform knowledge (the three OCI
-- upstreams), and a seeded repository row is not that.
