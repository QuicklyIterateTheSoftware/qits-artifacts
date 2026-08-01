package eu.wohlben.qits.artifacts.dto;

/**
 * The honesty panel: every way of saying how big this store is, named rather than reconciled.
 *
 * <p>They do not add up, and the gaps are large enough to look like a bug — which is the reason all
 * seven are reported together instead of one being picked. An unlabelled byte count over a
 * content-addressed, globally deduped store is a lie with a number in it.
 *
 * @param ociPerImageSumBytes the per-image unions, added. What the image list's column sums to, and
 *     about 8% above the truth, because a handful of blobs are shared between images.
 * @param ociUnionBytes every blob every manifest reaches, counted once. The true OCI figure.
 * @param orphanBytes blob files no manifest and no tarball row references. Not a rounding error:
 *     this store holds three ELF binaries uploaded through the OCI blob-upload session with no
 *     manifest and no row of any kind, so they are servable, reachable from nothing, and invisible
 *     to every view built on the database. There is no garbage collector to reclaim them.
 * @param npmPublishedBytes tarballs in the hosted npm repositories, deduped.
 * @param npmProxyTarballBytes tarballs cached from upstream, deduped.
 * @param npmProxyPackumentBytes the cached packument documents, which are H2 CLOBs and not files —
 *     nearly four times the tarballs they index, and the store's largest cost after image layers. A
 *     view reporting only the proxy's disk usage is off by that much. Counted in characters; the
 *     documents are ASCII JSON.
 * @param diskTotalBytes every blob file under the blob root, which is the number the filesystem
 *     agrees with. It exceeds the OCI union plus the npm figures by exactly {@link #orphanBytes}.
 */
public record StoreSummary(
    long ociPerImageSumBytes,
    long ociUnionBytes,
    long orphanBytes,
    long npmPublishedBytes,
    long npmProxyTarballBytes,
    long npmProxyPackumentBytes,
    long diskTotalBytes) {}
