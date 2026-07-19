-- Artifactory's own Flyway lineage (docs/epics/qits-artifactory/), on its OWN named datasource —
-- never mixed with qits' domain schema in db/migration. Two tables plus the metadata side-table.

-- A named, typed container. The name is the natural key; the type selects the validation profile.
create table artifact_repository (
    name varchar(255) not null,
    type varchar(64) not null check (type in ('CI_SCREENSHOTS','CI_VIDEOS')),
    created_at timestamp(6) with time zone not null,
    primary key (name)
);

-- One immutable metadata record per upload. blob_id is the SHA-256 of the content (hex); many
-- records may share one blob_id (dedupe) yet keep distinct rows. created_at is server-stamped.
create table artifact_record (
    id varchar(255) not null,
    repository varchar(255) not null,
    blob_id varchar(64) not null,
    mediatype varchar(255) not null,
    size_bytes bigint not null,
    created_at timestamp(6) with time zone not null,
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

alter table if exists artifact_record
    add constraint fk_artifact_record_repository foreign key (repository) references artifact_repository (name);
alter table if exists artifact_metadata
    add constraint fk_artifact_metadata_record foreign key (record_id) references artifact_record;
