-- qits-artifacts on PostgreSQL: the whole schema, from empty.
--
-- THIS IS A FRESH BASELINE, NOT A TRANSLATED CHAIN. The H2 lineage (V1..V14 under
-- db/artifacts/migration) went with the H2 driver. What is below is that chain's END STATE, written
-- once for the engine this service runs on now; git history keeps the fourteen migrations, and the
-- one-time H2+disk -> PostgreSQL copy is an ops tool that ships this schema rather than replaying
-- them. A new location as well as a new file, so a deployment carrying an H2 `flyway_schema_history`
-- cannot half-apply this.
--
-- Translation was mechanical where it could be: `clob` -> `text`, `timestamp(6) with time zone` ->
-- `timestamptz`. Every named foreign key, index and check keeps its name, because those names are
-- what the suite's refusal assertions read.
--
-- FOUR SHAPE DECISIONS, each a departure from a literal translation:
--
--   1. THE THREE GIT-HOST TABLES ARE OMITTED — `git_pack`, `git_pack_file` (H2 V4) and
--      `git_repository_protection` (H2 V5). They existed in that chain only because applied history
--      cannot be rewritten: the smart-HTTP host left for qits-githost in the byte-plane split, and
--      it owns that data in a database of its own. Nothing in this tree reads or writes them, and
--      the entities that did went with the host. A fresh baseline is the one place they can go.
--
--   2. THE FOUR CACHE TABLES STAY, AND STAY EMPTY — `oci_mirror_upstream`, `oci_mirror_tag_check`,
--      `npm_proxy_packument`, `maven_proxy_metadata`. The pull-through caches went to
--      qits-platform-mirror, but their repositories are LIVE BEANS on the qits-registries jars that
--      ride in here regardless: excluding a profile from bean discovery does not unregister a DAO.
--      `OciRegistryService.resolveForPull` reads the upstream table on every pull whose first
--      segment names no repository row, and `collectTag` deletes a freshness row for HOSTED tags
--      too. That is H2 V14's lesson, and the reason it emptied both instead of dropping them:
--      without the table an unknown-image pull is a 500 where it should be a 404 NAME_UNKNOWN.
--
--   3. NO MIRROR PREFILL. H2 V7 inserted three `oci-mirror` repository rows (`hub`, `quay`,
--      `redhat`) and their upstreams; H2 V14 took them out again, because a standing row is what
--      kept the mirror path reachable here after the code left. A fresh baseline simply never
--      writes them. Nothing else is prefilled either — the repository rows this service needs come
--      from `ArtifactsRepositorySeeder` at startup, and a migration prefills only static platform
--      knowledge.
--
--   4. `ck_artifact_repository_type` LISTS THE SEVEN TYPES THIS SERVICE REGISTERS, not the ten the
--      H2 chain accepted. That chain was carried through the split byte for byte and so still
--      admitted `NPM_PROXY`, `MAVEN_PROXY` and `OCI_MIRROR` long after nothing here could serve
--      one. A fresh baseline is where the set the code enforces and the set the database accepts
--      become one set, which is the property that makes either of them worth having:
--      `RepositoryTypeProfiles` indexes exactly these seven, `ArtifactRepositoryService.ensure`
--      refuses anything else with a 400, and this constraint says the same thing one layer down.
--      A migration copying rows in from a pre-split H2 store must skip cache-type repositories —
--      they are the dead data H2 V14 was written to remove.
--
-- Widening the constraint is still a one-liner (drop by name, re-add naming every key), and the
-- rule that governs it is unchanged: re-enumerate the whole list from the profiles as they stand in
-- the tree, never append.

-- --- the store ------------------------------------------------------------------------------------

-- A named, typed container. The name is the natural key; the type selects the validation profile.
create table artifact_repository (
    name varchar(255) not null,
    type varchar(64) not null,
    created_at timestamptz not null,
    primary key (name)
);

-- Named from the start, unlike H2 V1's inline check — an unnamed constraint gets an engine-chosen
-- name that the next widening has to look up before it can drop it.
alter table artifact_repository
    add constraint ck_artifact_repository_type
    check (type in ('CI_SCREENSHOTS','CI_VIDEOS','OCI_IMAGES','NPM_PACKAGES','MAVEN_PACKAGES','DAEMON_BINARIES','DOCS'));

-- One immutable metadata record per upload. blob_id is the SHA-256 of the content (hex); many
-- records may share one blob_id (dedupe) yet keep distinct rows. created_at is server-stamped.
-- accessed_at is nullable and NEVER backfilled: null has to keep meaning "never read since tracking
-- began", which is what a sweep must be able to tell apart from "read long ago".
create table artifact_record (
    id varchar(255) not null,
    repository varchar(255) not null,
    blob_id varchar(64) not null,
    mediatype varchar(255) not null,
    size_bytes bigint not null,
    created_at timestamptz not null,
    accessed_at timestamptz,
    primary key (id)
);

-- The flat string metadata map per record (@ElementCollection). Queryable exact-match predicates
-- join here; unknown keys are legal and stored opaquely.
create table artifact_metadata (
    record_id varchar(255) not null,
    meta_key varchar(255) not null,
    meta_value varchar(4000),
    primary key (record_id, meta_key)
);

create index idx_artifact_record_repository on artifact_record (repository);
create index idx_artifact_record_blob_id on artifact_record (blob_id);
create index idx_artifact_record_created_at on artifact_record (created_at);
create index idx_artifact_record_repository_accessed on artifact_record (repository, accessed_at);

alter table artifact_record
    add constraint fk_artifact_record_repository foreign key (repository) references artifact_repository (name);
alter table artifact_metadata
    add constraint fk_artifact_metadata_record foreign key (record_id) references artifact_record;

-- --- the OCI registry ----------------------------------------------------------------------------

-- The per-(repository, image) manifest registry.
--
-- Manifest BYTES are an ordinary blob — a manifest is content-addressed JSON — and get no row of
-- their own. THIS table is what scopes them to a name, so the globally-deduped blob store cannot
-- serve one repository's manifest out of another's namespace. It also carries the mediaType clients
-- dispatch on, so a digest-addressed GET answers with the right Content-Type without re-parsing the
-- JSON every time.
--
-- And it is what makes a multi-arch pull work at all: docker and buildx push an index by PUTting
-- each child manifest BY DIGEST, untagged, then the index by tag. A tag table alone cannot resolve
-- those children and every multi-arch pull would 404.
--
-- `digest` is the bare 64-hex, the same form as artifact_record.blob_id and the only form the blob
-- store speaks; the wire's `sha256:` prefix is stripped at the route boundary.
create table oci_manifest (
    repository varchar(255) not null,
    image_name varchar(255) not null,
    digest varchar(64) not null,
    media_type varchar(255) not null,
    size_bytes bigint not null,
    created_at timestamptz not null,
    accessed_at timestamptz,
    primary key (repository, image_name, digest)
);

-- The only MUTABLE state in the registry: a tag is a movable pointer at a manifest digest.
-- Re-pushing a tag updates the row. A manifest a tag used to point at stays reachable by digest.
create table oci_tag (
    repository varchar(255) not null,
    image_name varchar(255) not null,
    tag varchar(128) not null,
    manifest_digest varchar(64) not null,
    updated_at timestamptz not null,
    accessed_at timestamptz,
    primary key (repository, image_name, tag)
);

-- tags/list, and the manifest listing garbage collection walks.
create index idx_oci_manifest_image on oci_manifest (repository, image_name);
create index idx_oci_tag_image on oci_tag (repository, image_name);
create index idx_oci_manifest_image_accessed on oci_manifest (repository, image_name, accessed_at);
create index idx_oci_tag_image_accessed on oci_tag (repository, image_name, accessed_at);

-- Same-context foreign keys. (The "never a foreign key" rule in CLAUDE.md is about OTHER contexts'
-- tables; artifact_repository is this one's own.)
alter table oci_manifest
    add constraint fk_oci_manifest_repository foreign key (repository) references artifact_repository (name);
alter table oci_tag
    add constraint fk_oci_tag_repository foreign key (repository) references artifact_repository (name);

-- --- the npm registry ----------------------------------------------------------------------------

-- One published version of one package.
--
-- The TARBALL BYTES are an ordinary blob, keyed by their sha256 like everything else in this service
-- — `tarball_blob_id` is that key. npm's own two hashes are stored BESIDE it rather than instead of
-- it: `shasum` is sha1 and `integrity` is a base64 sha512 SRI string, neither of which the store can
-- address by, and both of which the client verifies end to end.
--
-- `manifest_json` is the version's manifest exactly as it arrived, so packument assembly is one
-- query and no field of a manifest has to be modelled here to survive. `text`, not a large object:
-- the entity binds it with @JdbcTypeCode(SqlTypes.LONGVARCHAR) precisely so serving a cached
-- document stays a read with no transaction.
--
-- Append-only: a version is immutable, a re-publish is 403, and the only delete is GC's.
create table npm_version (
    repository varchar(255) not null,
    package_name varchar(255) not null,
    version varchar(128) not null,
    tarball_blob_id varchar(64) not null,
    integrity varchar(255),
    shasum varchar(64),
    manifest_json text not null,
    created_at timestamptz not null,
    accessed_at timestamptz,
    primary key (repository, package_name, version)
);

-- The only MUTABLE table here, and the exact analog of oci_tag: a dist-tag is a movable pointer at a
-- version. `latest` moves on every publish; the version it used to name stays installable.
create table npm_dist_tag (
    repository varchar(255) not null,
    package_name varchar(255) not null,
    tag varchar(128) not null,
    version varchar(128) not null,
    updated_at timestamptz not null,
    primary key (repository, package_name, tag)
);

-- The republish tombstone: a version's identity, kept after its row is gone.
--
-- Version immutability is enforced by looking for the row, and garbage collection deletes rows.
-- Without this table the first GC of a prerelease would quietly re-open its name for a publish
-- carrying DIFFERENT bytes. A separate table rather than a flag on npm_version, because the
-- packument is assembled from those rows at read time and a marker column would need a `where`
-- clause in every reader — one a reader that does not know about this table cannot forget.
--
-- `tarball_blob_id` is provenance and is nullable: the identity is the point, and nothing may ever
-- be served from here.
create table npm_version_tombstone (
    repository varchar(255) not null,
    package_name varchar(255) not null,
    version varchar(128) not null,
    tarball_blob_id varchar(64),
    collected_at timestamptz not null,
    primary key (repository, package_name, version)
);

-- CACHE TABLE, EMPTY HERE — shape decision 2. The npm proxy's packument cache belongs to
-- qits-platform-mirror; `NpmProxyPackumentRepository` is a live bean on the qits-registries-npm jar
-- regardless, so the table has to exist for it to read nothing from.
create table npm_proxy_packument (
    repository varchar(255) not null,
    package_name varchar(255) not null,
    doc text not null,
    etag varchar(255),
    fetched_at timestamptz not null,
    primary key (repository, package_name)
);

-- Packument assembly walks every version and every dist-tag of one package.
create index idx_npm_version_package on npm_version (repository, package_name);
create index idx_npm_dist_tag_package on npm_dist_tag (repository, package_name);
create index idx_npm_version_package_accessed on npm_version (repository, package_name, accessed_at);

alter table npm_version
    add constraint fk_npm_version_repository foreign key (repository) references artifact_repository (name);
alter table npm_dist_tag
    add constraint fk_npm_dist_tag_repository foreign key (repository) references artifact_repository (name);
alter table npm_version_tombstone
    add constraint fk_npm_version_tombstone_repository foreign key (repository) references artifact_repository (name);
alter table npm_proxy_packument
    add constraint fk_npm_proxy_packument_repository foreign key (repository) references artifact_repository (name);

-- --- the OCI mirror's two tables, empty ----------------------------------------------------------
-- Shape decision 2 again, and the urgent half of it. `OciMirrorUpstreamRepository` is read by
-- `resolveForPull` on every pull whose first segment names no repository row, and
-- `OciMirrorTagCheckRepository` sits on this service's own GC funnel — `collectTag` deletes a
-- hosted tag's freshness row through it. Missing tables would turn both into runtime errors no
-- build here would show. NO PREFILL: with no upstream row `mirrors.hub()` answers nothing and the
-- remap never fires, which is what keeps the mirror path closed rather than merely unwanted.

create table oci_mirror_upstream (
    domain varchar(255) not null,
    slug varchar(255) not null,
    created_at timestamptz not null,
    primary key (domain),
    constraint uq_oci_mirror_upstream_slug unique (slug)
);

create table oci_mirror_tag_check (
    repository varchar(255) not null,
    image_name varchar(255) not null,
    tag varchar(128) not null,
    checked_at timestamptz not null,
    primary key (repository, image_name, tag)
);

alter table oci_mirror_tag_check
    add constraint fk_oci_mirror_tag_check_repository foreign key (repository) references artifact_repository (name);
alter table oci_mirror_upstream
    add constraint fk_oci_mirror_upstream_repository foreign key (slug) references artifact_repository (name);

-- --- the maven repository ------------------------------------------------------------------------

-- One deployed file of one maven repository: the path IS the identity.
--
-- The wire is a dumb path store on the maven layout: `mvn deploy` PUTs the jar, the pom and each
-- file's checksums one request at a time, and the path under the repository root is the only lookup
-- any of it needs. Both of maven's derived documents stay OUT of this table — maven-metadata.xml is
-- assembled per request from these rows and never stored, and checksums are derived at GET and
-- verified at PUT.
--
-- The BYTES are an ordinary blob keyed by sha256, so jars dedupe globally with image layers and npm
-- tarballs. size_bytes rides beside it, free at stage time.
create table maven_artifact (
    repository varchar(255) not null,
    path varchar(1024) not null,          -- the full maven-layout path, relative to the repo root
    blob_id varchar(64) not null,         -- sha256 of the bytes; the blob store key
    size_bytes bigint not null,
    created_at timestamptz not null,
    accessed_at timestamptz,
    primary key (repository, path)
);

-- CACHE TABLE, EMPTY HERE — shape decision 2. The cached maven-metadata.xml, the one maven document
-- that mutates. `MavenProxyMetadataRepository` rides in on the qits-registries-maven jar.
create table maven_proxy_metadata (
    repository varchar(255) not null,
    path varchar(1024) not null,
    doc text not null,
    etag varchar(255),
    last_modified varchar(64),
    fetched_at timestamptz not null,
    primary key (repository, path)
);

-- Metadata derivation prefix-scans `path like '<ga>/%'`: the primary key's leading columns are that
-- index, which is the right price at this store's scale.
create index idx_maven_artifact_repository_accessed on maven_artifact (repository, accessed_at);

alter table maven_artifact
    add constraint fk_maven_artifact_repository foreign key (repository) references artifact_repository (name);
alter table maven_proxy_metadata
    add constraint fk_maven_proxy_metadata_repository foreign key (repository) references artifact_repository (name);

-- --- the daemon binaries -------------------------------------------------------------------------

-- One published version of one platform daemon: (repository, name, version) IS the identity.
--
-- This table is the whole point of the type. Before it, the ci-daemon binary reached the store
-- through the OCI blob-upload session, which promotes bytes and writes no row by construction — so
-- every byte of it was row-less and invisible to every view built on the database. The row is
-- written in the SAME transaction as the publish, so identity-at-publish holds by construction.
--
-- Versions are IMMUTABLE: re-publishing an existing (name, version) is 409. NO PREFILL — adopting
-- blobs already in a live store is an ops action, because a migration cannot verify a digest
-- against the running store and a lineage must not embed live-platform digests.
create table daemon_binary (
    repository varchar(255) not null,
    name varchar(255) not null,        -- the daemon, e.g. qits-ci-daemon
    version varchar(128) not null,     -- calver from the release train; the digest hex for adopted rows
    blob_id varchar(64) not null,      -- sha256 of the bytes; the blob store key
    size_bytes bigint not null,
    published_at timestamptz not null,
    accessed_at timestamptz,
    primary key (repository, name, version)
);

create index idx_daemon_binary_name_accessed on daemon_binary (repository, name, accessed_at);

alter table daemon_binary
    add constraint fk_daemon_binary_repository foreign key (repository) references artifact_repository (name);

-- --- the docs sites ------------------------------------------------------------------------------

-- One published version of one documentation site: (repository, name, version) IS the identity, and
-- it is the ONLY identity this type has.
--
-- THAT IS THE WHOLE POINT OF THE TWO-TABLE SHAPE. A docs bundle is 50-odd files, and the thing that
-- must be evictable is the *version*, not a file inside one. So a file gets no identity of its own:
-- docs_file hangs off this row by the same three columns and cascades with it, which makes "evict
-- half a site" unrepresentable rather than merely discouraged.
--
-- total_bytes is the bundle as published, summed over its files, so a re-published asset is counted
-- by every version that references it; it sizes the SITE, not the disk. The disk answer is the
-- census's, which counts distinct blobs.
create table docs_site (
    repository varchar(255) not null,
    name varchar(255) not null,        -- the site, e.g. @qits/ui-components — scoped, may nest
    version varchar(128) not null,     -- calver from the release train
    file_count integer not null,
    total_bytes bigint not null,       -- the bundle as published, summed over its files
    published_at timestamptz not null,
    accessed_at timestamptz,           -- null = never served since tracking began
    primary key (repository, name, version)
);

-- One file inside one published version. No identity of its own: the primary key is the site's plus
-- the path, so a row cannot exist without its site and cannot outlive it.
--
-- media_type is resolved from the file EXTENSION at publish, not by MediaTypeSniffer — the sniffer
-- has no woff2 entry and would 400 on exactly the files a static site is made of. It is stored
-- rather than re-derived so the serve path is one row read and one send.
create table docs_file (
    repository varchar(255) not null,
    name varchar(255) not null,
    version varchar(128) not null,
    path varchar(1024) not null,       -- relative, no leading slash, no dot-segments
    blob_id varchar(64) not null,      -- sha256 of the bytes; the blob store key
    size_bytes bigint not null,
    media_type varchar(128) not null,
    primary key (repository, name, version, path)
);

alter table docs_site
    add constraint fk_docs_site_repository foreign key (repository) references artifact_repository (name);

-- ON DELETE CASCADE IS LOAD-BEARING, not convenience. It is what makes a version the unit of
-- eviction at the schema level: the collect path deletes one docs_site row and the database removes
-- its files, so no sweep, no bug and no hand-written query can leave a site half-collected and
-- serving 404s from a version that still lists itself.
alter table docs_file
    add constraint fk_docs_file_site foreign key (repository, name, version)
        references docs_site (repository, name, version) on delete cascade;

-- --- the blob store's three tables ---------------------------------------------------------------
-- COPIED VERBATIM from qits-blobstore's src/main/resources/db/blobstore-tables.sql, which is the
-- library's instruction: a lib owns no schema, so the service that owns the database pastes these
-- statements into a migration of its own and keeps the text identical so a later diff is readable.
-- That file is applied unedited by the library's own suite, so the DDL below is exercised on every
-- build of the jar as well as this one.

-- Content addressed by a surrogate id, so STAGING and PROMOTED bytes share one chunk table and
-- promote is a state flip rather than a copy. The cascade makes "discard a staging area" one
-- statement.
create table blob_content (
    content_id  uuid primary key,
    state       varchar(16) not null check (state in ('STAGING', 'PROMOTED')),
    started_at  timestamptz not null
);

-- One row per 1 MiB slice. STORAGE EXTERNAL skips TOAST compression: blob content is already
-- compressed (OCI layers, npm tarballs, git packs), so compressing again costs CPU for nothing.
create table blob_chunk (
    content_id  uuid not null references blob_content (content_id) on delete cascade,
    seq         integer not null,
    bytes       bytea not null,
    primary key (content_id, seq)
);
alter table blob_chunk alter column bytes set storage external;

-- The identity row: a SHA-256 content address bound to one content. `stored_at` replaces the file
-- mtime the store used to read for the garbage-collection grace window, and a dedupe does NOT
-- refresh it — parity with the old promote(), which was a no-op when the file already existed.
--
-- varchar, not char: PostgreSQL pads char to its full width. The check restates the store's
-- path-traversal defence at the table; the same rule stays in code.
create table blob (
    id          varchar(64) primary key check (id ~ '^[0-9a-f]{64}$'),
    content_id  uuid not null unique references blob_content (content_id),
    size_bytes  bigint not null,
    chunk_size  integer not null,
    stored_at   timestamptz not null
);

-- The staging sweep reads only STAGING rows, and they are a tiny minority of this table.
create index idx_blob_content_staging on blob_content (started_at) where state = 'STAGING';
