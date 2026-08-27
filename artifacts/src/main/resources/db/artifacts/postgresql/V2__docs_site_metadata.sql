-- Per-version metadata for docs bundles: the flat string map a publisher rides on
-- X-Artifacts-Meta-* headers, stored opaquely and queried exact-match — the artifact_metadata
-- shape, re-keyed onto docs_site's composite identity (artifact_metadata is keyed by an
-- artifact_record UUID, which a docs version does not have).
--
-- Column shapes mirror artifact_metadata verbatim (varchar 255/4000), so the wire caps in
-- ArtifactMetadataHeaders and the columns state one limit.
--
-- NO INDEX BEYOND THE PRIMARY KEY, and that is a decision: the one consumer query is "this site's
-- versions, filtered by metadata" (e.g. the latest bundle of one branch), which loads one site's
-- rows through the PK's leading (repository, name, version) and filters in Java — the
-- ArtifactQueryService stance at this cardinality. A cross-site (meta_key, meta_value) index would
-- answer a query no endpoint asks; the indexed metadata-join is the backlog trigger, there as here.
create table docs_site_metadata (
    repository varchar(255) not null,
    name varchar(255) not null,
    version varchar(128) not null,
    meta_key varchar(255) not null,
    meta_value varchar(4000),
    primary key (repository, name, version, meta_key)
);

-- ON DELETE CASCADE for the same load-bearing reason as fk_docs_file_site: the version is the unit
-- of eviction, so collecting one docs_site row removes its metadata with its files — no code path
-- can leave orphaned metadata describing a version that no longer lists itself.
alter table docs_site_metadata
    add constraint fk_docs_site_metadata_site foreign key (repository, name, version)
        references docs_site (repository, name, version) on delete cascade;
