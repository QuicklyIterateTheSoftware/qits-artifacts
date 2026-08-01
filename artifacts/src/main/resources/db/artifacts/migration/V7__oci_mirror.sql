-- The OCI pull-through mirror: a third protocol type, the upstream registry it fronts, and the tag
-- freshness table its miss path revalidates from. Same lineage and same named `artifacts` datasource
-- as V1/V2, which is NOT a squash baseline.
--
-- THE NUMBER. This lineage had three workstreams in flight at once, each widening or extending it
-- (proxy-pulling-normal-images.md §4). No plan reserves a number: each takes the next free V at land
-- time and re-enumerates the check constraint from the RepositoryType enum as it stands in the tree.
-- This one landed after V6 (the npm tombstone), so the list below is the enum's six constants.

-- V2 re-added the type check NAMED precisely so this stays a one-liner. `if exists` keeps it
-- re-runnable against a database where it has already gone.
alter table artifact_repository drop constraint if exists ck_artifact_repository_type;

alter table artifact_repository
    add constraint ck_artifact_repository_type
    check (type in ('CI_SCREENSHOTS','CI_VIDEOS','OCI_IMAGES','NPM_PACKAGES','NPM_PROXY','OCI_MIRROR'));

-- One upstream registry, and the local namespace segment that fronts it.
--
-- A TABLE RATHER THAN A CONFIG MAP, which is the user's ruling on ⚖1 and their reason for it:
-- config keys are invisible. A row has a CRUD API (/artifacts/api/mirror-upstreams) and a UI, so an
-- operator can see which upstreams this registry mirrors without reading a deployment's env.
--
-- `domain` is the upstream's IDENTITY — docker.io, quay.io, registry.access.redhat.com — and the
-- mirror derives the API endpoint from it (https://<domain>/v2/…, with the one well-known exception
-- docker.io → registry-1.docker.io). `slug` is what a puller writes: `docker pull
-- localhost:8081/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25`. It is unique because it is
-- a namespace, and it is a foreign key into artifact_repository because every upstream is PAIRED
-- with a repository row of type OCI_MIRROR — cached content lives in ordinary oci_manifest/oci_tag
-- rows under that name, so namespace resolution on a pull is a table read and never a config lookup.
--
-- Credentials are deliberately absent. Anonymous upstreams only at launch (⚖3): a client's `docker
-- login` does not traverse a pull-through hop — the mirror dials upstream as itself — so a private
-- registry needs a SERVER-side credential, which arrives here later as an additive column pair the
-- day a Hub 429 makes it necessary.
create table oci_mirror_upstream (
    domain varchar(255) not null,
    slug varchar(255) not null,
    created_at timestamp(6) with time zone not null,
    primary key (domain),
    constraint uq_oci_mirror_upstream_slug unique (slug)
);

-- What the mirror knows about a cached tag's freshness. An OCI tag is a movable pointer — `jdk-25`
-- and `9.6` are mutable upstream and move under toolchain and security updates — so it is the one
-- mirrored thing with a TTL. Everything else the mirror caches is addressed by digest and is kept
-- forever.
--
-- Nothing writes a row yet: BW ships the namespaces, BX ships the miss path that fills them. The
-- table ships here so the migration lineage is not re-opened for it, the same way the npm tombstone
-- shipped ahead of its only writer.
create table oci_mirror_tag_check (
    repository varchar(255) not null,
    image_name varchar(255) not null,
    tag varchar(128) not null,
    checked_at timestamp(6) with time zone not null,
    primary key (repository, image_name, tag)
);

-- Same-context foreign keys, exactly like V3's three. (The "never a foreign key" rule in AGENTS.md
-- is about OTHER contexts' tables; artifact_repository is ours.)
alter table if exists oci_mirror_tag_check
    add constraint fk_oci_mirror_tag_check_repository foreign key (repository) references artifact_repository (name);

-- The pairing invariant, in the schema rather than in a comment: an upstream's slug IS a repository
-- row. Deleting an upstream leaves that row and everything cached under it (⚖2, append-only), which
-- this direction of the key allows and the reverse would forbid.
alter table if exists oci_mirror_upstream
    add constraint fk_oci_mirror_upstream_repository foreign key (slug) references artifact_repository (name);

-- THE PREFILL. Three public registries with static domains, so unlike the daemon plan's live-platform
-- digests these belong in the lineage: a fresh deployment mirrors quay, Red Hat and Docker Hub with
-- no manual step, which is what makes a rewritten `FROM localhost:8081/quay/…` work on first boot.
--
-- The repository row goes FIRST because the upstream's slug references it. Both inserts are guarded
-- rather than blind: a deployment that already owns a repository named `hub`, `quay` or `redhat` of
-- some other type keeps it, this migration skips that pair instead of failing the boot, and an
-- operator can register the same upstream under a free slug through the CRUD API. Blind inserts
-- would take the git host down with them, and this service serves the push that redeploys it.
insert into artifact_repository (name, type, created_at)
select v.slug, 'OCI_MIRROR', current_timestamp
from (values ('hub'), ('quay'), ('redhat')) v(slug)
where not exists (select 1 from artifact_repository r where r.name = v.slug);

insert into oci_mirror_upstream (domain, slug, created_at)
select v.domain, v.slug, current_timestamp
from (values
        ('docker.io', 'hub'),
        ('quay.io', 'quay'),
        ('registry.access.redhat.com', 'redhat')) v(domain, slug)
where not exists (select 1 from oci_mirror_upstream u where u.domain = v.domain)
  and exists (select 1 from artifact_repository r where r.name = v.slug and r.type = 'OCI_MIRROR');
