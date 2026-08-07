-- The docs type: a published documentation site gets an identity, plus a widened type check for it.
-- Same lineage and same named `artifacts` datasource as V1/V2, which is NOT a squash baseline.
--
-- THE NUMBER. No plan reserves a migration number (V7's own header is the rule, and V10's records
-- what happens when one tries): each workstream takes the next free V at land time and
-- re-enumerates the check constraint from the RepositoryType enum as it stands in the tree. This
-- one landed after V11, so the list below is the enum's nine constants. OciMirrorMigrationTest
-- loops values() over this real directory, so a constant added without its widening fails the
-- suite, not the deployment.

-- V2 re-added the type check NAMED precisely so this stays a one-liner. `if exists` keeps it
-- re-runnable against a database where it has already gone.
alter table artifact_repository drop constraint if exists ck_artifact_repository_type;

alter table artifact_repository
    add constraint ck_artifact_repository_type
    check (type in ('CI_SCREENSHOTS','CI_VIDEOS','OCI_IMAGES','NPM_PACKAGES','NPM_PROXY','OCI_MIRROR','MAVEN_PACKAGES','DAEMON_BINARIES','DOCS'));

-- One published version of one documentation site: (repository, name, version) IS the identity, and
-- it is the ONLY identity this type has.
--
-- THAT IS THE WHOLE POINT OF THE TWO-TABLE SHAPE. A docs bundle is 50-odd files, and the thing that
-- must be evictable is the *version*, not a file inside one. So a file gets no identity of its own:
-- docs_file hangs off this row by the same three columns and cascades with it, which makes "evict
-- half a site" unrepresentable rather than merely discouraged. A GC strategy plans one candidate
-- per row here and never per path.
--
-- The BYTES of every file are ordinary BlobStore blobs keyed by sha256, so a docs bundle dedupes
-- globally with image layers, npm tarballs, maven jars and daemon binaries — and, far more often,
-- with its OWN previous versions: fonts and unchanged chunks are byte-identical across releases and
-- are stored once. total_bytes is the sum over the bundle as published, so a re-published asset is
-- counted by every version that references it; it sizes the SITE, not the disk. The disk answer is
-- the census's, which counts distinct blobs.
--
-- Versions are IMMUTABLE: re-publishing an existing (name, version) is 409, the stance
-- daemon_binary takes. That is what makes a version-addressed URL safe to hand out and to bookmark
-- — a pointer that never moves — and it is why qits-docs can be stateless: `latest` is a query over
-- these rows, not a mutable alias someone has to keep correct.
create table docs_site (
    repository varchar(255) not null,
    name varchar(255) not null,        -- the site, e.g. @qits/ui-components — scoped, may nest
    version varchar(128) not null,     -- calver from the release train
    file_count integer not null,
    total_bytes bigint not null,       -- the bundle as published, summed over its files
    published_at timestamp(6) with time zone not null,
    accessed_at timestamp(6) with time zone,   -- null = never served since tracking began (V11)
    primary key (repository, name, version)
);

-- One file inside one published version. No identity of its own: the primary key is the site's plus
-- the path, so a row cannot exist without its site and cannot outlive it.
--
-- media_type is resolved from the file EXTENSION at publish, not by MediaTypeSniffer — the sniffer
-- has no woff2 entry and would 400 on exactly the files a static site is made of. It is stored
-- rather than re-derived so the serve path is one row read and a sendFile.
create table docs_file (
    repository varchar(255) not null,
    name varchar(255) not null,
    version varchar(128) not null,
    path varchar(1024) not null,       -- relative, no leading slash, no dot-segments
    blob_id varchar(64) not null,      -- sha256 of the bytes; the BlobStore key
    size_bytes bigint not null,
    media_type varchar(128) not null,
    primary key (repository, name, version, path)
);

-- Listing a site's versions, and listing a version's files, both scan the primary keys' leading
-- columns — the right price at this store's scale.

-- Same-context foreign keys, exactly like V3's three, V7's one, V8's and V10's. (The "never a
-- foreign key" rule in AGENTS.md is about OTHER contexts' tables; these are ours.)
alter table if exists docs_site
    add constraint fk_docs_site_repository foreign key (repository) references artifact_repository (name);

-- ON DELETE CASCADE IS LOAD-BEARING, not convenience. It is what makes a version the unit of
-- eviction at the schema level: the collect path deletes one docs_site row and the database removes
-- its files, so no sweep, no bug and no hand-written query can leave a site half-collected and
-- serving 404s from a version that still lists itself.
alter table if exists docs_file
    add constraint fk_docs_file_site foreign key (repository, name, version)
        references docs_site (repository, name, version) on delete cascade;
