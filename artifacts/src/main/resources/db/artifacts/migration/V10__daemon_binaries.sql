-- The daemon-binaries type: the platform's own executables get an identity, plus a widened type
-- check for it. Same lineage and same named `artifacts` datasource as V1/V2, which is NOT a squash
-- baseline.
--
-- THE NUMBER. No plan reserves a migration number (V7's own header is the rule): each workstream
-- takes the next free V at land time and re-enumerates the check constraint from the RepositoryType
-- enum as it stands in the tree. daemon-artifact-identity-plan.md §2 says "this is V6" — it was
-- written when the lineage ended at V5 and four migrations landed behind it. This one landed after
-- V9, so the list below is the enum's eight constants. OciMirrorMigrationTest loops values() over
-- this real directory, so a constant added without its widening fails the suite, not the deployment.

-- V2 re-added the type check NAMED precisely so this stays a one-liner. `if exists` keeps it
-- re-runnable against a database where it has already gone.
alter table artifact_repository drop constraint if exists ck_artifact_repository_type;

alter table artifact_repository
    add constraint ck_artifact_repository_type
    check (type in ('CI_SCREENSHOTS','CI_VIDEOS','OCI_IMAGES','NPM_PACKAGES','NPM_PROXY','OCI_MIRROR','MAVEN_PACKAGES','DAEMON_BINARIES'));

-- One published version of one platform daemon: (repository, name, version) IS the identity.
--
-- This table is the whole point of the type. Before it, the ci-daemon binary reached the store
-- through the OCI blob-upload session, which promotes bytes and writes no row by construction — so
-- every byte of it was row-less, invisible to every view built on the database, and reported as an
-- orphan. The row is written in the SAME transaction as the publish, so identity-at-publish holds
-- by construction and no backfill path has to exist for anything published from here on.
--
-- The BYTES are an ordinary BlobStore blob keyed by sha256 — blob_id is that key — so a daemon
-- binary dedupes globally with image layers, npm tarballs and maven jars. size_bytes rides beside
-- it, free at stage time: this is the second protocol table the census sizes without a disk read.
--
-- Versions are IMMUTABLE: re-publishing an existing (name, version) is 409, npm's stance. That is
-- what makes the version-addressed download route safe to add beside the digest-addressed blob
-- route — a version pointer that never moves is as self-verifying as a digest, and latest-wins can
-- never happen.
--
-- NO PREFILL. Adopting the three ELF blobs already on the live volume is an ops action, not a
-- migration (daemon-artifact-identity-plan.md §5 step 2): the lineage must not embed live-platform
-- digests, and a migration cannot verify one against the running store.
create table daemon_binary (
    repository varchar(255) not null,
    name varchar(255) not null,        -- the daemon, e.g. qits-ci-daemon
    version varchar(128) not null,     -- calver from the release train; the digest hex for adopted rows
    blob_id varchar(64) not null,      -- sha256 of the bytes; the BlobStore key
    size_bytes bigint not null,
    published_at timestamp(6) with time zone not null,
    primary key (repository, name, version)
);

-- Listing a daemon's versions scans the primary key's leading columns, which is the right price at
-- this store's scale (one daemon, a handful of releases).

-- Same-context foreign key, exactly like V3's three, V7's one and V8's. (The "never a foreign key"
-- rule in AGENTS.md is about OTHER contexts' tables; artifact_repository is ours.)
alter table if exists daemon_binary
    add constraint fk_daemon_binary_repository foreign key (repository) references artifact_repository (name);
