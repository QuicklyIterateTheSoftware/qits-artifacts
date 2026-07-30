-- The npm registry: a hosted repository type and a pull-through cache of an upstream, three tables,
-- plus a widened type check for both. Same lineage and same named `artifacts` datasource as V1/V2,
-- which is NOT a squash baseline.

-- V2 re-added the type check NAMED precisely so this would be a one-liner. `if exists` keeps it
-- re-runnable against a database where it has already gone.
alter table artifact_repository drop constraint if exists ck_artifact_repository_type;

alter table artifact_repository
    add constraint ck_artifact_repository_type
    check (type in ('CI_SCREENSHOTS','CI_VIDEOS','OCI_IMAGES','NPM_PACKAGES','NPM_PROXY'));

-- One published (or proxied) version of one package.
--
-- The TARBALL BYTES are an ordinary BlobStore blob, keyed by their sha256 like everything else in
-- this service — `tarball_blob_id` is that key. npm's own two hashes are stored BESIDE it rather
-- than instead of it: `shasum` is sha1 and `integrity` is a base64 sha512 SRI string, neither of
-- which the store can address by, and both of which the client verifies end to end. Recomputed on
-- publish and re-emitted verbatim in packuments; for a proxied version they are upstream's values,
-- untouched, which is what keeps the proxy incapable of silently corrupting a package.
--
-- `manifest_json` is the version's manifest exactly as it arrived, so packument assembly is one
-- query and no field of a manifest has to be modelled here to survive. Only `dist` is replaced at
-- serve time, because a tarball URL is a property of the request's authority, not of the package.
--
-- Append-only: a version is immutable, a re-publish is 403, and there is no delete.
create table npm_version (
    repository varchar(255) not null,
    package_name varchar(255) not null,
    version varchar(128) not null,
    tarball_blob_id varchar(64) not null,
    integrity varchar(255),
    shasum varchar(64),
    manifest_json clob not null,
    created_at timestamp(6) with time zone not null,
    primary key (repository, package_name, version)
);

-- The only MUTABLE table here, and the exact analog of oci_tag: a dist-tag is a movable pointer at
-- a version. `latest` moves on every publish; the version it used to name stays installable.
create table npm_dist_tag (
    repository varchar(255) not null,
    package_name varchar(255) not null,
    tag varchar(128) not null,
    version varchar(128) not null,
    updated_at timestamp(6) with time zone not null,
    primary key (repository, package_name, tag)
);

-- The proxy's packument cache. Packuments are the one npm document that genuinely mutates — a new
-- version appears upstream without anything here changing — so unlike a tarball this is cached with
-- a TTL (qits.artifacts.npm.proxy.packument-ttl) and revalidated with the stored `etag`.
--
-- `doc` is upstream's document VERBATIM. It is not rewritten before storage even though every
-- `dist.tarball` in it must point back at this proxy when served, for two reasons: the rewrite
-- target depends on the request (X-Forwarded-Host, else the authority actually dialled), so a
-- stored rewrite would be wrong for half the callers; and the original URLs are what the tarball
-- miss path fetches from, so discarding them would strand any package whose tarballs are not on
-- upstream's canonical /<pkg>/-/<file> layout.
create table npm_proxy_packument (
    repository varchar(255) not null,
    package_name varchar(255) not null,
    doc clob not null,
    etag varchar(255),
    fetched_at timestamp(6) with time zone not null,
    primary key (repository, package_name)
);

-- Packument assembly walks every version and every dist-tag of one package.
create index idx_npm_version_package on npm_version (repository, package_name);
create index idx_npm_dist_tag_package on npm_dist_tag (repository, package_name);

-- Same-context foreign keys, exactly like V1's fk_artifact_record_repository and V2's. (The "never
-- a foreign key" rule in AGENTS.md is about OTHER contexts' tables; artifact_repository is ours.)
alter table if exists npm_version
    add constraint fk_npm_version_repository foreign key (repository) references artifact_repository (name);
alter table if exists npm_dist_tag
    add constraint fk_npm_dist_tag_repository foreign key (repository) references artifact_repository (name);
alter table if exists npm_proxy_packument
    add constraint fk_npm_proxy_packument_repository foreign key (repository) references artifact_repository (name);
