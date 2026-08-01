-- The per-repository default-branch protection override, moved off the bare's own git config.
--
-- WHY IT MOVED. `ProtectedRefHook` read `[qits] protectDefaultBranch` straight out of the
-- repository JGit had already opened, so a deployment exempted one repo by writing one line into a
-- file that travelled with the volume. A DFS-backed repository has no such file: its `getConfig()`
-- is an in-memory `DfsConfig` whose load and save are no-ops, so that read would silently answer
-- the platform default for every repository, forever, with no symptom.
--
-- ONE MECHANISM, NOT TWO. This row is the override source for BOTH backends, not just the new one.
-- Leaving the file backend on the config file would mean the same question had two answers that
-- could disagree, and the disagreement would surface as a push that was refused on one storage
-- engine and accepted on the other. The platform-wide default is unchanged and still
-- `qits.repositories.git.protect-default-branch`; absent row means "no override", exactly as an
-- absent config line did.
--
-- No backfill is needed: every bare on the live volume was checked at the time this shipped and
-- none carried a `[qits] protectDefaultBranch` line — the platform-wide default has been the only
-- answer in production so far.

create table git_repository_protection (
    repository_id varchar(255) not null,
    protect_default_branch boolean not null,
    updated_at timestamp(6) with time zone not null,
    primary key (repository_id)
);
