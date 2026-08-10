package eu.wohlben.qits.artifacts.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The proxy's cached packument for one upstream package.
 *
 * <p>A packument is the one npm document that genuinely mutates — a new version appears upstream
 * with nothing here changing — so this is cached with a TTL ({@code
 * qits.artifacts.npm.proxy.packument-ttl}) and revalidated with {@link #etag}, unlike a tarball,
 * which is immutable and cached forever. When upstream cannot be reached the stale document is
 * served anyway: CI keeps installing through an npmjs outage, which is half of why the proxy exists.
 *
 * <p>{@link #doc} is upstream's document <b>verbatim</b>, and that is deliberate even though every
 * {@code dist.tarball} in it has to point back at this proxy by the time a client sees one. The
 * rewrite target depends on the request — {@code X-Forwarded-Host} through the gateway, the
 * authority actually dialled on qits-net — so a stored rewrite would be wrong for half the callers;
 * and the original URLs are exactly what the tarball miss path fetches from, so discarding them
 * would strand any package whose tarballs do not sit on upstream's canonical layout.
 */
@Entity
@Table(name = "npm_proxy_packument")
@IdClass(NpmProxyPackumentId.class)
public class NpmProxyPackument extends PanacheEntityBase {

  @Id public String repository;

  @Id
  @Column(name = "package_name")
  public String packageName;

  @Lob
  @Column(nullable = false)
  public String doc;

  /** Upstream's validator, replayed as {@code If-None-Match} once the TTL expires. */
  public String etag;

  @Column(name = "fetched_at", nullable = false)
  public Instant fetchedAt;
}
