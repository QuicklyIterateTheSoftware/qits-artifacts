-- The SBOM store: one CycloneDX document per published artifact, keyed by the SoftwareRelease
-- identity (packageType, packageName, version) so a release event resolves straight to its SBOM.
--
-- Widened by RE-ENUMERATION, never by appending (V1's rule, restated where it is exercised): the
-- constraint is dropped by name and re-added listing every key the profiles register as the tree
-- stands, now eight with SBOMS.
alter table artifact_repository drop constraint ck_artifact_repository_type;
alter table artifact_repository
    add constraint ck_artifact_repository_type
    check (type in ('CI_SCREENSHOTS','CI_VIDEOS','OCI_IMAGES','NPM_PACKAGES','MAVEN_PACKAGES','DAEMON_BINARIES','DOCS','SBOMS'));

-- One row per stored document. The identity IS the SoftwareRelease identity: package_type is the
-- pipeline's declared artifact type verbatim (npm|maven|docker|daemon), package_name the unqualified
-- name exactly as declared ("qits/qits-artifacts", "@qits/ui-components",
-- "eu.wohlben.qits:qits-eventstream"), version the released calver. The bytes are an ordinary
-- content-addressed blob, so two identical documents store once.
--
-- package_name is varchar(512) rather than 255: a maven coordinate carries groupId AND artifactId
-- in one value, and 255 is snug exactly where a rename would be quietest.
--
-- accessed_at is nullable and NEVER backfilled — null keeps meaning "never read since stored",
-- the same contract every protocol table here carries.
create table sbom_document (
    repository   varchar(255) not null,
    package_type varchar(16)  not null,
    package_name varchar(512) not null,
    version      varchar(128) not null,
    blob_id      varchar(64)  not null,
    size_bytes   bigint       not null,
    spec_version varchar(8)   not null,
    created_at   timestamptz  not null,
    accessed_at  timestamptz,
    primary key (repository, package_type, package_name, version)
);

-- The four types a SoftwareRelease can declare. Named, like every check here, so the next widening
-- drops it by name instead of looking an engine-chosen one up first.
alter table sbom_document
    add constraint ck_sbom_document_package_type
    check (package_type in ('npm','maven','docker','daemon'));

alter table sbom_document
    add constraint fk_sbom_document_repository
    foreign key (repository) references artifact_repository (name);

-- The one read pattern beyond the PK: a package's versions newest-first, and the access basis the
-- GC window reads. The PK's leading columns answer the exact-identity lookup already.
create index idx_sbom_document_name_accessed
    on sbom_document (repository, package_type, package_name, accessed_at);
