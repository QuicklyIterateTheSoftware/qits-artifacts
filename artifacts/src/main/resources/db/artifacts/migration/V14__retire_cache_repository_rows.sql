-- Retires the pull-through caches' rows. The three cache repository types — `oci-mirror`,
-- `npm-proxy` and `maven-proxy` — and every line of code that serves them went to
-- qits-platform-mirror in the byte-plane split. This service registers no profile for any of them
-- (`quarkus.arc.exclude-types` vetoes the three profile beans), so their rows are dead data twice
-- over: the explorer listing omits an unclaimed type, the GC plan refuses it, and the store census
-- counts their bytes nowhere.
--
-- THE NUMBER. No plan reserves one (V7's header is the rule): each workstream takes the next free V
-- at land time. The plan behind this one guessed V14 and V13 landed first, so V14 it is — by
-- arithmetic, not by reservation. This migration re-enumerates nothing: it removes rows, and
-- `ck_artifact_repository_type` still accepts all ten keys because the chain is carried through the
-- split byte for byte and a mirror on its own schema declares the same constraint.
--
-- WHY THE OCI HALF IS THE URGENT ONE. V7 *prefills* three `oci-mirror` rows — `hub`, `quay`,
-- `redhat` — so every fresh database here grows them, and the wire code that reads them ships in the
-- qits-registries-oci jar whether or not the profile is registered. Excluding a bean does not
-- unregister a route's inject: `OciRegistryService.resolveForPull` matches the type by STRING, so a
-- prefilled namespace still resolves for a `GET` and a first segment naming no repository still
-- remaps into `hub`. The row is what keeps that path reachable. Removing it is what closes it.
--
-- WHY NPM AND MAVEN GO TOO, although the plan named only the mirror. No migration ever prefilled
-- them (V13's header says so in as many words) and `ArtifactsRepositorySeeder` stopped seeding
-- `npmjs` and `central` at the split, so a fresh database has none — but a deployment carried
-- through the split still holds both, dead in exactly the same way. Leaving them for a second
-- migration would be a second migration.

-- WHAT STAYS, AND WHY IT IS NOT AN OVERSIGHT.
--
-- `oci_mirror_upstream` is EMPTIED, not dropped. `OciMirrorUpstreamRepository` is a live bean here —
-- it rides in on the same jar, and `resolveForPull` reads it on every pull whose first segment names
-- no repository row. Dropping the table turns that read into a missing-table error, so an unknown
-- image name would answer `500` instead of the `404 NAME_UNKNOWN` a client can act on. Empty is what
-- actually closes the path: `mirrors.hub()` answers nothing and the remap never fires.
delete from oci_mirror_upstream;

-- `oci_mirror_tag_check` keeps its table for the same class of reason: `OciRegistryService.collectTag`
-- deletes a tag's row through it for HOSTED tags too, so it sits on this service's own GC funnel.
-- Only the cache repositories' rows go — and they have to go first regardless, because the table's
-- foreign key into `artifact_repository` would otherwise refuse the delete below.
delete from oci_mirror_tag_check
where repository in (select name from artifact_repository where type = 'OCI_MIRROR');

-- The two cached DOCUMENTS reference no blob — a packument and a maven-metadata.xml are clobs — so
-- deleting them strands nothing and lets the repository row below actually go on a carried-over
-- deployment that only ever read metadata.
delete from npm_proxy_packument
where repository in (select name from artifact_repository where type = 'NPM_PROXY');

delete from maven_proxy_metadata
where repository in (select name from artifact_repository where type = 'MAVEN_PROXY');

-- The repository rows themselves — GUARDED, and the guard is the point rather than caution.
--
-- A cache repository with content still cached under it is LEFT STANDING. Deleting the row would
-- need its `oci_manifest`/`oci_tag`/`npm_version`/`maven_artifact` rows to go with it (the foreign
-- keys say so), and a blob that loses its last identity row becomes ROW-LESS — which this store's
-- GC cannot reach by construction, so the migration would leak those bytes forever. That is the one
-- thing "Row-less blobs are untouchable" forbids anyone from doing on purpose. A fresh database has
-- no such rows and loses all three `oci-mirror` rows here; a deployment that really cached something
-- keeps it, and reclaiming it is an ops action against a store qits-platform-mirror now owns.
--
-- Every table with a foreign key into `artifact_repository` is listed, including the two no cache
-- row could plausibly be in (`daemon_binary`, `docs_site`). A guard that misses one is a failed
-- constraint at migrate time, and this service serves the pushes that redeploy the platform — a
-- migration here may not take the boot down.
delete from artifact_repository
where type in ('OCI_MIRROR', 'NPM_PROXY', 'MAVEN_PROXY')
  and not exists (select 1 from artifact_record x where x.repository = artifact_repository.name)
  and not exists (select 1 from oci_manifest x where x.repository = artifact_repository.name)
  and not exists (select 1 from oci_tag x where x.repository = artifact_repository.name)
  and not exists (select 1 from oci_mirror_tag_check x where x.repository = artifact_repository.name)
  and not exists (select 1 from npm_version x where x.repository = artifact_repository.name)
  and not exists (select 1 from npm_dist_tag x where x.repository = artifact_repository.name)
  and not exists (select 1 from npm_proxy_packument x where x.repository = artifact_repository.name)
  and not exists (select 1 from npm_version_tombstone x where x.repository = artifact_repository.name)
  and not exists (select 1 from maven_artifact x where x.repository = artifact_repository.name)
  and not exists (select 1 from maven_proxy_metadata x where x.repository = artifact_repository.name)
  and not exists (select 1 from daemon_binary x where x.repository = artifact_repository.name)
  and not exists (select 1 from docs_site x where x.repository = artifact_repository.name)
  and not exists (select 1 from oci_mirror_upstream x where x.slug = artifact_repository.name);
