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
 * The generated files are cached in {@link BenchmarkDatasets#BENCH_DIR} and are
 * never stored in this repository.
 */
public class SyntheticImages {

	private static final int TILE = 1024;

	/** Default size of the generated images, close to a VSI fluorescent series */
	public static final int SIZE_X = 8000;
	public static final int SIZE_Y = 6000;
	public static final int SIZE_C = 3;

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
				writer.saveBytes(c, plane(sizeX, sizeY, c));
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

	/** @return one 16 bits plane, big endian */
	private static byte[] plane(int sizeX, int sizeY, int channel) {
		byte[] bytes = new byte[sizeX * sizeY * 2];
		for (int y = 0; y < sizeY; y++) {
			for (int x = 0; x < sizeX; x++) {
				int v = value(x, y, channel);
				int offset = 2 * (y * sizeX + x);
				bytes[offset] = (byte) (v >>> 8);
				bytes[offset + 1] = (byte) v;
			}
		}
		return bytes;
	}

	/** Generates the synthetic files used by the benchmarks */
	public static void main(String... args) throws Exception {
		System.out.println("Generated " + uint16File());
	}
}
