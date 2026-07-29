-- The OCI Distribution registry: two tables, plus a widened type check for OCI_IMAGES.
-- Same lineage and same named `artifacts` datasource as V1, which is NOT a squash baseline.

-- V1 declares the type check INLINE (line 7), so it is unnamed and H2 auto-generated a name for it.
-- That name is an artifact of the DDL order inside V1, not a contract, so it must be looked up
-- rather than hardcoded. Re-added NAMED afterwards, which makes the next widening a one-liner.
-- The coalesce keeps this re-runnable against a database where the check has already gone.
execute immediate coalesce(
    (select 'alter table artifact_repository drop constraint "' || constraint_name || '"'
       from information_schema.table_constraints
      where table_schema = current_schema
        and table_name = 'ARTIFACT_REPOSITORY'
        and constraint_type = 'CHECK'),
    'set @noop = 0');

alter table artifact_repository
    add constraint ck_artifact_repository_type
    check (type in ('CI_SCREENSHOTS','CI_VIDEOS','OCI_IMAGES'));

-- The per-(repository, image) manifest registry.
--
-- Manifest BYTES are an ordinary BlobStore blob — a manifest is content-addressed JSON — and get no
-- row of their own. THIS table is what scopes them to a name, so the globally-deduped blob store
-- cannot serve one repository's manifest out of another's namespace. It also carries the mediaType
-- clients dispatch on, so a digest-addressed GET answers with the right Content-Type without
-- re-parsing the JSON every time.
--
-- And it is what makes a multi-arch pull work at all: docker and buildx push an index by PUTting
-- each child manifest BY DIGEST, untagged, then the index by tag. A tag table alone cannot resolve
-- those children and every multi-arch pull would 404.
--
-- `digest` is the bare 64-hex, the same form as artifact_record.blob_id and the only form BlobStore
-- speaks; the wire's `sha256:` prefix is stripped at the route boundary.
create table oci_manifest (
    repository varchar(255) not null,
    image_name varchar(255) not null,
    digest varchar(64) not null,
    media_type varchar(255) not null,
    size_bytes bigint not null,
    created_at timestamp(6) with time zone not null,
    primary key (repository, image_name, digest)
);

-- The only MUTABLE state in the registry: a tag is a movable pointer at a manifest digest.
-- Re-pushing a tag updates the row. Nothing is ever deleted (garbage collection is out of scope and
-- the DELETE endpoints are deliberately unimplemented), so a manifest a tag used to point at stays
-- reachable by digest.
create table oci_tag (
    repository varchar(255) not null,
    image_name varchar(255) not null,
    tag varchar(128) not null,
    manifest_digest varchar(64) not null,
    updated_at timestamp(6) with time zone not null,
    primary key (repository, image_name, tag)
);

-- tags/list, and the manifest listing a future GC would have to walk.
create index idx_oci_manifest_image on oci_manifest (repository, image_name);
create index idx_oci_tag_image on oci_tag (repository, image_name);

-- Same-context foreign keys, exactly like V1's fk_artifact_record_repository. (The "never a foreign
-- key" rule in CLAUDE.md is about OTHER contexts' tables; artifact_repository is this one's own.)
alter table if exists oci_manifest
    add constraint fk_oci_manifest_repository foreign key (repository) references artifact_repository (name);
alter table if exists oci_tag
    add constraint fk_oci_tag_repository foreign key (repository) references artifact_repository (name);
