-- The maven pull-through cache: a second maven type, the one table its mutable document needs, and
-- a widened type check for it. Same lineage and same named `artifacts` datasource as V1/V2, which is
-- NOT a squash baseline.
--
-- THE NUMBER. No plan reserves a migration number (V7's header is the rule, V10's records what
-- happens when one tries): each workstream takes the next free V at land time and re-enumerates the
-- check constraint from the RepositoryType enum as it stands in the tree. This one landed after V12,
-- so the list below is the enum's ten constants. OciMirrorMigrationTest loops values() over this
-- real directory, so a constant added without its widening fails the suite, not the deployment.

-- V2 re-added the type check NAMED precisely so this stays a one-liner. `if exists` keeps it
-- re-runnable against a database where it has already gone.
alter table artifact_repository drop constraint if exists ck_artifact_repository_type;

alter table artifact_repository
    add constraint ck_artifact_repository_type
    check (type in ('CI_SCREENSHOTS','CI_VIDEOS','OCI_IMAGES','NPM_PACKAGES','NPM_PROXY','OCI_MIRROR','MAVEN_PACKAGES','MAVEN_PROXY','DAEMON_BINARIES','DOCS'));

-- NO SECOND ARTIFACT TABLE, and that is the point of the shape rather than an economy.
--
-- A cached jar is an ordinary maven_artifact row under the proxy's own repository name: same path
-- key, same blob_id, same size_bytes, same accessed_at. Every reader already attributes those rows
-- by their repository's TYPE — the census does it in one loop, the explorer in one switch — so the
-- pull-through cache arrives with no new liveness code and no second definition of what a stored
-- maven file is. The npm proxy settled this shape first (npm_version holds hosted and proxied
-- versions alike); the eviction doors check the repository's type, which is what keeps the two kinds
-- of row from being confused for one another.
--
-- Upstream's own .sha1/.md5/.sha256/.sha512 files are rows here too. They are immutable paths like
-- any other, and caching upstream's copy rather than deriving one locally is what keeps the client's
-- verification END TO END: a checksum this service computed from bytes this service downloaded would
-- agree with itself whatever arrived.

-- The one maven document that mutates. maven-metadata.xml lists the versions upstream has, so a new
-- release changes it with nothing here changing — it cannot be an immutable path, and it cannot be
-- derived from the cached rows either, because those are the versions this cache happens to hold
-- rather than the versions that exist. So it is cached with a TTL
-- (qits.artifacts.maven.proxy.metadata-ttl) and revalidated with etag/last_modified on expiry, the
-- npm_proxy_packument pattern verbatim.
--
-- `doc` is upstream's document VERBATIM and needs no rewrite at serve time: maven metadata carries
-- versions, not URLs, which is the one way it is simpler than a packument. That is also what makes
-- the derived checksum honest — the bytes served and the bytes hashed are the same bytes, so
-- maven-metadata.xml.sha1 can never disagree with the document beside it. Proxying upstream's copy
-- of that checksum instead would pair a cached document with a hash of a NEWER one and fail every
-- client that checks.
--
-- Two validators, because maven repositories are older than universal ETag support: Central answers
-- both, a mirror behind a plain file server may answer only Last-Modified, and either one turns an
-- expiry into a 304 rather than a document.
create table maven_proxy_metadata (
    repository varchar(255) not null,
    path varchar(1024) not null,       -- e.g. org/slf4j/slf4j-api/maven-metadata.xml
    doc clob not null,
    etag varchar(255),
    last_modified varchar(64),
    fetched_at timestamp(6) with time zone not null,
    primary key (repository, path)
);

-- Same-context foreign key, exactly like V3's three, V7's one, V8's, V10's and V12's. (The "never a
-- foreign key" rule in AGENTS.md is about OTHER contexts' tables; artifact_repository is ours.)
alter table if exists maven_proxy_metadata
    add constraint fk_maven_proxy_metadata_repository foreign key (repository) references artifact_repository (name);

-- NO PREFILL: the `central` repository row comes from the startup seeder, matching how `npm`,
-- `npmjs`, `maven` and `qits` arrived. Migrations prefill only static platform knowledge (the three
-- OCI upstreams), and a seeded repository row is not that.
