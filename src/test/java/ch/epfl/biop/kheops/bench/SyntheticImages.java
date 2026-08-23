/*-
 * #%L
 * IJ2 commands that use bio-formats to create pyramidal ome.tiff
 * %%
 * Copyright (C) 2018 - 2026 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package ch.epfl.biop.kheops.bench;

import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.formats.out.OMETiffWriter;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.integer.UnsignedShortType;

import java.io.File;

/**
 * Synthetic images for the benchmarks.
 * <p>
 * They serve as a reference point: their content is either already in memory or
 * stored uncompressed in a plain tiled OME-TIFF, so reading them costs almost
 * nothing. Comparing an export of a synthetic image with an export of a real
 * acquisition of the same size is what separates a decoding bottleneck from a
 * writing one.
 * <p>
 * The files are written one tile at a time, never one plane at a time, so that
 * the size of a generated image is not bounded by what fits in a single
 * {@code byte[]}. That is what makes {@link #giantUint16File()} possible at any
 * size: a single plane past 32404x32404 uint16 holds more than
 * {@link Integer#MAX_VALUE} bytes and could not be built plane by plane.
 * <p>
 * The generated files are cached in {@link BenchmarkDatasets#BENCH_DIR} and are
 * never stored in this repository.
 */
public class SyntheticImages {

	private static final int TILE = 1024;

	/**
	 * Default size of the generated images: a few times the sides of a VSI
	 * fluorescent series, so that a default export takes on the order of ten
	 * seconds. Throughput rises with size, so the sides are not a plain multiple
	 * of the original 8000x6000 - they were fitted to the target time.
	 */
	public static final int SIZE_X = 22000;
	public static final int SIZE_Y = 16000;
	public static final int SIZE_C = 3;

	/**
	 * Side of {@link #giantUint16File()}. The default is a single uint16 plane of
	 * 32000x32000, 1.9 GB, which downsamples to 6 resolution levels: one plane
	 * with none of the per plane overhead the other datasets carry, sized so that
	 * a default export takes on the order of ten seconds. It is deliberately not
	 * a multiple of {@link #TILE}, so that the partial tiles of the right and
	 * bottom edges are exercised too. Raise it past 32404 with
	 * {@code -Dkheops.bench.giantSize=...} for a plane whose pixels no longer fit
	 * in a single {@code byte[]}; the tile wise writing below has no such limit.
	 */
	public static final int GIANT_SIZE = Integer.getInteger(
		"kheops.bench.giantSize", 32000);

	/**
	 * @param channel changes the pattern from one channel to the next
	 * @return a 16 bits image held in memory, as a XYZ interval with a single z
	 *         slice
	 */
	public static RandomAccessibleInterval<UnsignedShortType> uint16Image(
		int sizeX, int sizeY, int channel)
	{
		short[] pixels = new short[sizeX * sizeY];
		for (int y = 0; y < sizeY; y++) {
			for (int x = 0; x < sizeX; x++) {
				pixels[y * sizeX + x] = (short) value(x, y, channel);
			}
		}
		return ArrayImgs.unsignedShorts(pixels, sizeX, sizeY, 1);
	}

	/**
	 * A structured pattern rather than noise: an image which compresses a little
	 * is closer to a real acquisition than pure noise, which LZW cannot compress
	 * at all.
	 */
	private static int value(int x, int y, int channel) {
		int cell = ((x >> 5) + (y >> 5) + channel) & 0xF;
		return (cell << 11) + ((x * 7 + y * 13 + channel * 1024) & 0x7FF);
	}

	/**
	 * @return an uncompressed, tiled, single resolution OME-TIFF holding
	 *         {@link #SIZE_X} x {@link #SIZE_Y} pixels and {@link #SIZE_C}
	 *         channels of 16 bits data, generated once and then cached
	 */
	public static File uint16File() throws Exception {
		return uint16File(SIZE_X, SIZE_Y, SIZE_C);
	}

	/**
	 * @return one uint16 plane of {@link #GIANT_SIZE} x {@link #GIANT_SIZE}
	 *         pixels: a deep pyramid built from a single plane, with none of the
	 *         per plane overhead the other datasets carry
	 */
	public static File giantUint16File() throws Exception {
		return uint16File(GIANT_SIZE, GIANT_SIZE, 1);
	}

	/**
	 * Number of series in {@link #multiSeriesUint16File()}, and the side of each
	 * of them. The defaults give 16 series of 8192x8192 uint16, 2 GB in total,
	 * each downsampling to 4 resolution levels.
	 */
	public static final int MULTI_SERIES_COUNT = Integer.getInteger(
		"kheops.bench.series", 16);
	public static final int MULTI_SERIES_SIZE = Integer.getInteger(
		"kheops.bench.seriesSize", 8192);

	/**
	 * @return a single OME-TIFF holding {@link #MULTI_SERIES_COUNT} series, the
	 *         shape of the input {@code KheopsCommand} exports one series per
	 *         thread from. Every series carries a different pattern, so a series
	 *         written to the wrong place is visible rather than silent.
	 */
	public static File multiSeriesUint16File() throws Exception {
		return multiSeriesUint16File(MULTI_SERIES_COUNT, MULTI_SERIES_SIZE,
			MULTI_SERIES_SIZE, 1);
	}

	public static File multiSeriesUint16File(int nSeries, int sizeX, int sizeY,
		int sizeC) throws Exception
	{
		File dir = BenchmarkDatasets.BENCH_DIR;
		if (!dir.exists() && !dir.mkdirs()) {
			throw new IllegalStateException("Could not create " + dir);
		}
		File file = new File(dir, "synthetic_uint16_" + nSeries + "series_" +
			sizeX + "x" + sizeY + "_" + sizeC + "c.ome.tiff");
		if (file.exists()) return file;

		System.out.println("Generating " + file + " (" + String.format("%.0f",
			(double) nSeries * sizeX * sizeY * sizeC * 2 / (1024 * 1024)) + " MB, " +
			nSeries + " series)");
		File partial = new File(dir, file.getName() + ".partial");
		if (partial.exists() && !partial.delete()) {
			throw new IllegalStateException("Could not delete " + partial);
		}

		IMetadata meta = MetadataTools.createOMEXMLMetadata();
		for (int s = 0; s < nSeries; s++) {
			MetadataTools.populateMetadata(meta, s, "series_" + s, false, "XYZCT",
				"uint16", sizeX, sizeY, 1, sizeC, 1, 1);
		}
		OMETiffWriter writer = new OMETiffWriter();
		try {
			writer.setMetadataRetrieve(meta);
			writer.setBigTiff(true);
			writer.setCompression("Uncompressed");
			writer.setId(partial.getAbsolutePath());
			writer.setTileSizeX(TILE);
			writer.setTileSizeY(TILE);
			for (int s = 0; s < nSeries; s++) {
				writer.setSeries(s);
				System.out.println("  series " + s + " of " + nSeries);
				for (int c = 0; c < sizeC; c++) {
					// the pattern is offset by the series index, so two series never
					// hold the same pixels
					writeTiles(writer, sizeX, sizeY, c, s * sizeC + c);
				}
			}
		}
		finally {
			writer.close();
		}
		if (!partial.renameTo(file)) {
			throw new IllegalStateException("Could not rename " + partial + " to " +
				file);
		}
		return file;
	}

	/**
	 * Shape of {@link #manyPlanesUint16File()}: 1250 timepoints of 2 channels at
	 * 1024x1024, so 2500 planes for 5 GB. A 1024 pixel plane is not larger than
	 * the 1024 pixel tile, so the export builds a single resolution level and
	 * writes one TIFF directory per plane - this dataset is almost pure per plane
	 * overhead, the opposite end of {@link #giantUint16File()}.
	 */
	public static final int MANY_PLANES_SIZE = Integer.getInteger(
		"kheops.bench.planeSize", 1024);
	public static final int MANY_PLANES_T = Integer.getInteger(
		"kheops.bench.timepoints", 1250);
	public static final int MANY_PLANES_C = Integer.getInteger(
		"kheops.bench.planeChannels", 2);

	public static File manyPlanesUint16File() throws Exception {
		return manyPlanesUint16File(MANY_PLANES_SIZE, MANY_PLANES_T,
			MANY_PLANES_C);
	}

	public static File manyPlanesUint16File(int size, int sizeT, int sizeC)
		throws Exception
	{
		File dir = BenchmarkDatasets.BENCH_DIR;
		if (!dir.exists() && !dir.mkdirs()) {
			throw new IllegalStateException("Could not create " + dir);
		}
		File file = new File(dir, "synthetic_uint16_" + size + "x" + size + "_" +
			sizeT + "t_" + sizeC + "c.ome.tiff");
		if (file.exists()) return file;

		int planes = sizeT * sizeC;
		System.out.println("Generating " + file + " (" + String.format("%.0f",
			(double) planes * size * size * 2 / (1024 * 1024)) + " MB, " + planes +
			" planes)");
		File partial = new File(dir, file.getName() + ".partial");
		if (partial.exists() && !partial.delete()) {
			throw new IllegalStateException("Could not delete " + partial);
		}

		IMetadata meta = MetadataTools.createOMEXMLMetadata();
		MetadataTools.populateMetadata(meta, 0, "manyplanes", false, "XYZCT",
			"uint16", size, size, 1, sizeC, sizeT, 1);
		OMETiffWriter writer = new OMETiffWriter();
		try {
			writer.setMetadataRetrieve(meta);
			writer.setBigTiff(true);
			writer.setCompression("Uncompressed");
			writer.setId(partial.getAbsolutePath());
			// A plane smaller than the tile is written whole: asking a TiffWriter for
			// tiles larger than the image is not worth the trouble
			if (size > TILE) {
				writer.setTileSizeX(TILE);
				writer.setTileSizeY(TILE);
			}
			int lastPercent = -1;
			for (int t = 0; t < sizeT; t++) {
				for (int c = 0; c < sizeC; c++) {
					// XYZCT with a single z slice
					int no = c + sizeC * t;
					writeTiles(writer, size, size, no, no);
					int percent = 100 * (no + 1) / planes;
					if (percent / 10 != lastPercent / 10) {
						System.out.println("  " + percent + "% (" + (no + 1) + " / " +
							planes + " planes)");
						lastPercent = percent;
					}
				}
			}
		}
		finally {
			writer.close();
		}
		if (!partial.renameTo(file)) {
			throw new IllegalStateException("Could not rename " + partial + " to " +
				file);
		}
		return file;
	}

	public static File uint16File(int sizeX, int sizeY, int sizeC)
		throws Exception
	{
		File dir = BenchmarkDatasets.BENCH_DIR;
		if (!dir.exists() && !dir.mkdirs()) {
			throw new IllegalStateException("Could not create " + dir);
		}
		File file = new File(dir, "synthetic_uint16_" + sizeX + "x" + sizeY + "_" +
			sizeC + "c.ome.tiff");
		if (file.exists()) return file;

		System.out.println("Generating " + file + " (" + String.format("%.0f",
			(double) sizeX * sizeY * sizeC * 2 / (1024 * 1024)) + " MB)");
		File partial = new File(dir, file.getName() + ".partial");
		if (partial.exists() && !partial.delete()) {
			throw new IllegalStateException("Could not delete " + partial);
		}

		IMetadata meta = MetadataTools.createOMEXMLMetadata();
		MetadataTools.populateMetadata(meta, 0, "synthetic", false, "XYZCT",
			"uint16", sizeX, sizeY, 1, sizeC, 1, 1);
		OMETiffWriter writer = new OMETiffWriter();
		try {
			writer.setMetadataRetrieve(meta);
			writer.setBigTiff(true);
			writer.setCompression("Uncompressed");
			writer.setId(partial.getAbsolutePath());
			writer.setTileSizeX(TILE);
			writer.setTileSizeY(TILE);
			for (int c = 0; c < sizeC; c++) {
				writeTiles(writer, sizeX, sizeY, c);
			}
		}
		finally {
			writer.close();
		}
		if (!partial.renameTo(file)) {
			throw new IllegalStateException("Could not rename " + partial + " to " +
				file);
		}
		return file;
	}

	/**
	 * Writes one plane tile by tile. The tiles of the right and bottom edges are
	 * clipped to the image, which is what {@code TiffWriter} expects: it pads
	 * them itself when it lays the tile out on disk.
	 */
	private static void writeTiles(OMETiffWriter writer, int sizeX, int sizeY,
		int plane) throws Exception
	{
		writeTiles(writer, sizeX, sizeY, plane, plane);
	}

	/**
	 * @param plane the index the tiles are written at
	 * @param pattern the value fed to {@link #value}, kept apart from the plane
	 *          index so that two series of a multi series file never hold the
	 *          same pixels
	 */
	private static void writeTiles(OMETiffWriter writer, int sizeX, int sizeY,
		int plane, int pattern) throws Exception
	{
		int tilesX = (sizeX + TILE - 1) / TILE;
		int tilesY = (sizeY + TILE - 1) / TILE;
		long total = (long) tilesX * tilesY;
		long done = 0;
		int lastPercent = -1;
		for (int tileY = 0; tileY < tilesY; tileY++) {
			int y = tileY * TILE;
			int h = Math.min(TILE, sizeY - y);
			for (int tileX = 0; tileX < tilesX; tileX++) {
				int x = tileX * TILE;
				int w = Math.min(TILE, sizeX - x);
				writer.saveBytes(plane, tile(x, y, w, h, pattern), x, y, w, h);
				done++;
				int percent = (int) (100 * done / total);
				// a plane which is a single tile reports nothing: the many planes
				// dataset has a thousand of them and prints its own progress
				if (total > 1 && percent / 10 != lastPercent / 10) {
					System.out.print("  plane " + plane + ": " + percent + "%\r");
					lastPercent = percent;
				}
			}
		}
		if (total > 1) System.out.println("  plane " + plane + ": done      ");
	}

	/** @return one 16 bits tile of the plane, big endian */
	private static byte[] tile(int offsetX, int offsetY, int w, int h,
		int channel)
	{
		byte[] bytes = new byte[w * h * 2];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int v = value(offsetX + x, offsetY + y, channel);
				int offset = 2 * (y * w + x);
				bytes[offset] = (byte) (v >>> 8);
				bytes[offset + 1] = (byte) v;
			}
		}
		return bytes;
	}

	/**
	 * Generates the synthetic files used by the benchmarks. The default
	 * 8000x6000 image is always built; pass {@code giant} or {@code multiseries}
	 * to build the large ones too, which are not made by default because they
	 * take a while and occupy 488 MB and 512 MB respectively.
	 */
	public static void main(String... args) throws Exception {
		System.out.println("Generated " + uint16File());
		for (String arg : args) {
			if ("giant".equalsIgnoreCase(arg)) {
				System.out.println("Generated " + giantUint16File());
			}
			if ("multiseries".equalsIgnoreCase(arg)) {
				System.out.println("Generated " + multiSeriesUint16File());
			}
			if ("manyplanes".equalsIgnoreCase(arg)) {
				System.out.println("Generated " + manyPlanesUint16File());
			}
		}
	}
}
