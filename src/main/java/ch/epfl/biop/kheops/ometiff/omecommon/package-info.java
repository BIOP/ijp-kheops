/**
 * Temporary local copy of two ome-common classes. <b>Delete this package as
 * soon as the upstream issue is fixed.</b>
 *
 * <h2>Why it exists</h2>
 *
 * {@code loci.common.AbstractNIOHandle.validateLength} extends the output file
 * on <i>every</i> write that goes past its end, and
 * {@code loci.common.NIOFileHandle.setLength} turns that into a
 * {@code RandomAccessFile.setLength} system call. {@code TiffSaver} writes an
 * IFD field by field, so one image directory costs about a hundred of those
 * calls. Exporting a file with many small planes - 84 series x 90 z x 2
 * channels is 15 120 IFDs, roughly 1.5 M extending writes - spends about 70 %
 * of its time there, and it does not get better with more threads, because
 * extending a file serializes in the file system.
 *
 * <h2>Why a copy rather than a subclass</h2>
 *
 * The fix needs the real end of the file, and that is only knowable inside
 * {@code NIOFileHandle}'s private write path: the bulk
 * {@code write(ByteBuffer, int, int)} skips {@code validateLength} altogether
 * and lets {@code channel.write} resize the file, and {@code doWrite} can write
 * further than {@code setLength} was ever told. A subclass sees neither -
 * measured attempts over- and under-estimated the end and silently corrupted
 * the pixels - so the class has to be copied to be fixed.
 *
 * <h2>What is copied</h2>
 *
 * Two classes: {@code NIOFileHandle} (as {@link
 * ch.epfl.biop.kheops.ometiff.omecommon.PreallocatingFileHandle}, amended) and
 * {@code AbstractNIOHandle} (unmodified). The second is copied only so that the
 * first does not inherit from whichever ome-common a given Fiji installation
 * ships - its {@code validateLength} is the behaviour being worked around.
 * {@code IRandomAccess} stays the library's type, because that is what
 * {@code Location.mapFile} accepts; {@code NIOByteBufferProvider} is still used
 * from the library, and {@code Constants.ENCODING} is a compile-time constant,
 * so it leaves no runtime dependency.
 *
 * <h2>Upstream</h2>
 *
 * <a href="https://github.com/ome/ome-common-java/issues/119">ome-common-java#119</a>
 * is the open issue, with a standalone benchmark.
 * <a href="https://github.com/ome/ome-common-java/issues/78">ome-common-java#78</a>
 * (closed as not planned in Nov 2024) and
 * <a href="https://github.com/ome/bioformats/pull/3680">bioformats#3680</a>
 * (closed unmerged) describe the same problem. As of ome-common 6.3.0 the code
 * is unchanged.
 *
 * <h2>Careful with the license plugin</h2>
 *
 * The two copied files keep ome's BSD-2-Clause header, which their license
 * requires. {@code mvn license:update-file-header} would replace it with this
 * project's GPL header - do not run it over this package.
 *
 * <h2>How to remove it</h2>
 *
 * When a released ome-common buffers its small writes or allows a growth
 * increment, delete this package and the {@code FastOutput} try-with-resources
 * in {@code OMETiffExporter.export()}. Nothing else refers to it.
 */
package ch.epfl.biop.kheops.ometiff.omecommon;
