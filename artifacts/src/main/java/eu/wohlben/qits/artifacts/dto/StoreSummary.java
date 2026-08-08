package eu.wohlben.qits.artifacts.dto;

/**
 * The honesty panel: every way of saying how big this store is, named rather than reconciled.
 *
 * <p>They do not add up, and the gaps are large enough to look like a bug — which is the reason all
 * eleven are reported together instead of one being picked. An unlabelled byte count over a
 * content-addressed, globally deduped store is a lie with a number in it.
 *
 * @param ociPerImageSumBytes the per-image unions, added. What the image list's column sums to, and
 *     about 8% above the truth, because a handful of blobs are shared between images.
 * @param ociUnionBytes every blob every manifest reaches, counted once. The true OCI figure — for the
 *     <b>hosted</b> repositories; what a mirror pulled through is {@link #ociMirrorBytes}.
 * @param ociMirrorBytes every blob the mirror namespaces' manifests reach, counted once. Reported
 *     beside the hosted union rather than folded into it, because the two answer different
 *     questions: one is what this platform published, the other is what it cached from three public
 *     registries and could re-fetch. It is also the figure that stops growing once the base images
 *     settle, which is the whole claim the pull-through cache makes.
 * @param orphanBytes blob files no identity row of any type references. It used to be exactly three
 *     ELF binaries uploaded through the OCI blob-upload session with no manifest and no row of any
 *     kind — servable, reachable from nothing, and invisible to every view built on the database.
 *     {@link #daemonBinaryBytes} is where those bytes belong now; the ones already on a deployment's
 *     volume move across when they are adopted, and this figure reading zero is the proof that
 *     happened (daemon-artifact-identity-plan.md §5 step 4).
 * @param npmPublishedBytes tarballs in the hosted npm repositories, deduped.
 * @param npmProxyTarballBytes tarballs cached from upstream, deduped.
 * @param npmProxyPackumentBytes the cached packument documents, which are H2 CLOBs and not files —
 *     nearly four times the tarballs they index, and the store's largest cost after image layers. A
 *     view reporting only the proxy's disk usage is off by that much. Counted in characters; the
 *     documents are ASCII JSON.
 * @param mavenPublishedBytes jars, poms and everything else deployed to the hosted maven
 *     repositories, deduped and sized from the rows — {@code maven_artifact} is the one protocol
 *     table that carries its size.
 * @param mavenProxyBytes jars, poms and checksum files cached from an upstream maven repository
 *     (Maven Central by default), deduped and sized from the same rows — one table holds both maven
 *     types and the census tells them apart by their repository's type. Reported beside the hosted
 *     figure rather than folded into it, for {@link #ociMirrorBytes}' reason: one is what this
 *     platform published, the other is what it cached and could re-fetch. The cached {@code
 *     maven-metadata.xml} documents are H2 CLOBs and are in no figure here, the way {@link
 *     #npmProxyPackumentBytes} is — they are kilobytes rather than that figure's hundreds of
 *     megabytes, so the GC report's note carries the character count instead.
 * @param daemonBinaryBytes the platform's own daemon executables, deduped and sized from the rows —
 *     {@code daemon_binary} is the second protocol table that carries its size. Reported as its own
 *     figure rather than folded into any other because it is the one class whose bytes a running
 *     service downloads and executes: it has to be legible on the kept side of every report.
 * @param diskTotalBytes every blob file under the blob root, which is the number the filesystem
 *     agrees with. It exceeds the two OCI unions plus the npm, maven and daemon figures by exactly {@link
 *     #orphanBytes} — provided no blob is reached by two types at once, which content addressing
 *     permits and nothing forbids. That is the one way these figures can over-count, and it is the
 *     reason the census, not this record, is what a sweep reconciles over.
 */
public record StoreSummary(
    long ociPerImageSumBytes,
    long ociUnionBytes,
    long ociMirrorBytes,
    long orphanBytes,
    long npmPublishedBytes,
    long npmProxyTarballBytes,
    long npmProxyPackumentBytes,
    long mavenPublishedBytes,
    long mavenProxyBytes,
    long daemonBinaryBytes,
    long diskTotalBytes) {}
