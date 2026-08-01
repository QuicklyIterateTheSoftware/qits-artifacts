package eu.wohlben.qits.artifacts.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A movable pointer from a dist-tag to a version — the npm registry's only mutable state, and the
 * exact analog of {@link OciTag}.
 *
 * <p>{@code latest} moves on every publish; the version it used to name stays installable by exact
 * version, because {@link NpmVersion} rows are append-only. Nothing else the npm side writes can
 * change after it is written.
 *
 * <p>Movable is not unordered: {@code latest} may only ever move <b>forward</b> by semver
 * precedence, so a prerelease can never claim it. Every other tag moves freely. The rule lives in
 * {@code NpmRegistryService.requireLatestMayMoveTo}, not here — it is registry policy rather than a
 * property of the row.
 */
@Entity
@Table(name = "npm_dist_tag")
@IdClass(NpmDistTagId.class)
public class NpmDistTag extends PanacheEntityBase {

  @Id public String repository;

  @Id
  @Column(name = "package_name")
  public String packageName;

  @Id
  @Column(length = 128)
  public String tag;

  @Column(nullable = false, length = 128)
  public String version;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
