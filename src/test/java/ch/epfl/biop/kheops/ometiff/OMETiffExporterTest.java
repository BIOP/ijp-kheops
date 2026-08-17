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
package ch.epfl.biop.kheops.ometiff;

import loci.common.DebugTools;
import loci.common.RandomAccessInputStream;
import loci.formats.ImageReader;
import loci.formats.tiff.IFD;
import loci.formats.tiff.TiffParser;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.Views;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests of the OME-TIFF export, with a focus on the size of the exported file:
 * the tiles of a TIFF file are always fully written, even when they extend
 * beyond the image boundaries. The exporter thus has to adapt the tile size to
 * each resolution level, otherwise a lot of padding pixels are written, see
 * <a href="https://github.com/BIOP/ijp-kheops/issues/22">issue #22</a>.
 */
public class OMETiffExporterTest {

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@BeforeClass
	public static void silenceBioFormats() {
		DebugTools.setRootLevel("OFF");
	}

	// --------------------------------------------------- synthetic test images

	/** @return the value of the synthetic image at the given position */
	private static int value(int x, int y) {
		return (x * 3 + y * 7) % 251;
	}

	private static RandomAccessibleInterval<UnsignedByteType> grayImage(int sizeX,
		int sizeY)
	{
		FunctionRandomAccessible<UnsignedByteType> fn =
			new FunctionRandomAccessible<>(2, (position, pixel) -> pixel.set(value(
				position.getIntPosition(0), position.getIntPosition(1))),
				UnsignedByteType::new);
		return Views.interval(fn, new FinalInterval(new long[] { 0, 0 }, new long[] {
			sizeX - 1, sizeY - 1 }));
	}

	private static RandomAccessibleInterval<ARGBType> rgbImage(int sizeX,
		int sizeY)
	{
		FunctionRandomAccessible<ARGBType> fn = new FunctionRandomAccessible<>(2, (
			position, pixel) -> {
			int x = position.getIntPosition(0);
			int y = position.getIntPosition(1);
			pixel.set(ARGBType.rgba(value(x, y), value(x, y + 1), value(x + 1, y),
				255));
		}, ARGBType::new);
		return Views.interval(fn, new FinalInterval(new long[] { 0, 0 }, new long[] {
			sizeX - 1, sizeY - 1 }));
	}

	// ------------------------------------------------------------------ export

	private File export(RandomAccessibleInterval<?> image, String name,
		int tileSize, int nResolutions, boolean uncompressed) throws Exception
	{
		File file = new File(folder.getRoot(), name + ".ome.tiff");
		OMETiffExporter.OMETiffExporterBuilder.WriterOptions.WriterOptionsBuilder builder =
			OMETiffExporter.builder().putXYZRAI((RandomAccessibleInterval) image)
				.defineMetaData("Image").defineWriteOptions().tileSize(tileSize,
					tileSize).nResolutionLevels(nResolutions).compressTemporaryFiles(false)
				.savePath(file.getAbsolutePath());
		if (uncompressed) builder.uncompressed();
		builder.create().export();
		return file;
	}

	// ------------------------------------------------------------ measurements

	/**
	 * @return the number of bytes effectively occupied by the pixels of all the
	 *         IFDs of a TIFF file - which includes the padding of the tiles which
	 *         are partially outside of the image
	 */
	private static long pixelBytes(File file) throws Exception {
		long total = 0;
		try (RandomAccessInputStream in = new RandomAccessInputStream(file
			.getAbsolutePath()))
		{
			List<IFD> ifds = new TiffParser(in).getIFDs();
			for (IFD ifd : ifds) {
				for (long byteCount : ifd.getStripByteCounts()) {
					total += byteCount;
				}
			}
		}
		return total;
	}

	/** @return the number of pixels of a pyramid, all resolution levels included */
	private static long pyramidPixels(int sizeX, int sizeY, int nResolutions) {
		long total = 0;
		for (int r = 0; r < nResolutions; r++) {
			total += (long) (int) (sizeX / Math.pow(2, r)) * (int) (sizeY / Math.pow(2,
				r));
		}
		return total;
	}

	// ------------------------------------------------------------------- tests

	@Test
	public void adjustedTileSizeIsValid() {
		int[] requestedSizes = { 16, 64, 128, 500, 512, 1000, 1024, 4096 };
		int[] imageSizes = { 1, 15, 16, 17, 250, 375, 999, 1000, 1024, 3000,
			10000 };
		for (int requested : requestedSizes) {
			// Tile size effectively used by the TIFF writer for the requested size
			int rounded = Math.max(OMETiffExporter.TILE_GRANULARITY, Math.round(
				requested / (float) OMETiffExporter.TILE_GRANULARITY) *
				OMETiffExporter.TILE_GRANULARITY);
			for (int imageSize : imageSizes) {
				int tileSize = OMETiffExporter.adjustTileSize(requested, imageSize);
				String context = "requested tile size " + requested + ", image size " +
					imageSize;
				assertEquals(context + ": tile size should be a multiple of " +
					OMETiffExporter.TILE_GRANULARITY, 0, tileSize %
						OMETiffExporter.TILE_GRANULARITY);
				assertTrue(context + ": tile size should be strictly positive",
					tileSize > 0);
				assertTrue(context + ": tile size should never exceed the requested one",
					tileSize <= rounded);
				// The number of tiles should not be increased by the adjustment
				int nTiles = (int) Math.ceil(imageSize / (double) tileSize);
				int nTilesRequested = (int) Math.ceil(imageSize / (double) rounded);
				assertTrue(context + ": " + nTiles + " tiles instead of " +
					nTilesRequested, nTiles <= nTilesRequested);
				// And the padding should stay small
				assertTrue(context + ": too much padding (" + nTiles * tileSize +
					" pixels written for " + imageSize + ")", nTiles * tileSize <=
						imageSize + nTiles * OMETiffExporter.TILE_GRANULARITY);
			}
		}
	}

	/**
	 * The size of an uncompressed file should be close to the size of the raw
	 * pixel data, whatever the requested tile size - this is the issue #22.
	 */
	@Test
	public void uncompressedFileSizeIsIndependentOfTileSize() throws Exception {
		int sizeX = 1000, sizeY = 800, nResolutions = 4;
		long rawBytes = pyramidPixels(sizeX, sizeY, nResolutions);
		long previous = -1;
		for (int tileSize : new int[] { 128, 512, 1024 }) {
			File file = export(grayImage(sizeX, sizeY), "size_tile" + tileSize,
				tileSize, nResolutions, true);
			long written = pixelBytes(file);
			// Some padding is unavoidable: the tiles of a TIFF file have a size
			// which is a multiple of 16 pixels
			assertTrue("Tile size " + tileSize + ": " + written +
				" pixel bytes written for " + rawBytes + " raw bytes", written < 1.2 *
					rawBytes);
			// Bigger tiles should not lead to a bigger file
			if (previous > 0) {
				assertTrue("Increasing the tile size to " + tileSize +
					" increased the file size (" + previous + " -> " + written + ")",
					written < 1.02 * previous);
			}
			previous = written;
		}
	}

	/** The tile size of a resolution level should never exceed its size */
	@Test
	public void tilesAreNotBiggerThanTheImage() throws Exception {
		int sizeX = 700, sizeY = 300, nResolutions = 3;
		File file = export(grayImage(sizeX, sizeY), "smallimage", 2048,
			nResolutions, true);
		try (RandomAccessInputStream in = new RandomAccessInputStream(file
			.getAbsolutePath()))
		{
			List<IFD> ifds = new TiffParser(in).getIFDs();
			assertEquals(nResolutions, ifds.size());
			for (IFD ifd : ifds) {
				long width = ifd.getImageWidth();
				long height = ifd.getImageLength();
				assertTrue("Tile width " + ifd.getTileWidth() + " for an image of width " +
					width, ifd.getTileWidth() <= width + OMETiffExporter.TILE_GRANULARITY);
				assertTrue("Tile height " + ifd.getTileLength() +
					" for an image of height " + height, ifd
						.getTileLength() <= height + OMETiffExporter.TILE_GRANULARITY);
			}
		}
	}

	/** The pixels of the full resolution level should be left untouched */
	@Test
	public void pixelsAreUnchanged() throws Exception {
		int sizeX = 613, sizeY = 227; // Not a multiple of any tile size
		for (int tileSize : new int[] { 128, 512, 1024 }) {
			File file = export(grayImage(sizeX, sizeY), "pixels_tile" + tileSize,
				tileSize, 3, true);
			ImageReader reader = new ImageReader();
			try {
				reader.setFlattenedResolutions(false);
				reader.setId(file.getAbsolutePath());
				reader.setSeries(0);
				reader.setResolution(0);
				assertEquals(sizeX, reader.getSizeX());
				assertEquals(sizeY, reader.getSizeY());
				byte[] plane = reader.openBytes(0);
				for (int y = 0; y < sizeY; y++) {
					for (int x = 0; x < sizeX; x++) {
						assertEquals("Tile size " + tileSize + ", pixel (" + x + ", " + y +
							")", value(x, y), plane[y * sizeX + x] & 0xFF);
					}
				}
			}
			finally {
				reader.close();
			}
		}
	}

	/** The downsampled levels should hold the average of the level above */
	@Test
	public void pyramidLevelsAreDownsampled() throws Exception {
		int sizeX = 613, sizeY = 227, nResolutions = 3;
		for (int tileSize : new int[] { 128, 1024 }) {
			File file = export(grayImage(sizeX, sizeY), "pyramid_tile" + tileSize,
				tileSize, nResolutions, true);
			ImageReader reader = new ImageReader();
			try {
				reader.setFlattenedResolutions(false);
				reader.setId(file.getAbsolutePath());
				reader.setSeries(0);
				assertEquals(nResolutions, reader.getResolutionCount());
				reader.setResolution(1);
				int width = reader.getSizeX();
				int height = reader.getSizeY();
				assertEquals(sizeX / 2, width);
				assertEquals(sizeY / 2, height);
				byte[] plane = reader.openBytes(0);
				for (int y = 0; y < height; y++) {
					for (int x = 0; x < width; x++) {
						int expected = (value(2 * x, 2 * y) + value(2 * x + 1, 2 * y) + value(
							2 * x, 2 * y + 1) + value(2 * x + 1, 2 * y + 1)) / 4;
						assertEquals("Tile size " + tileSize + ", downsampled pixel (" + x +
							", " + y + ")", expected, plane[y * width + x] & 0xFF, 1);
					}
				}
			}
			finally {
				reader.close();
			}
		}
	}

	/** Several channels, z slices and timepoints, RGB pixels */
	@Test
	public void rgbMultiDimensionalExportIsValid() throws Exception {
		int sizeX = 613, sizeY = 227, nResolutions = 2;
		File file = new File(folder.getRoot(), "rgb.ome.tiff");
		OMETiffExporter.builder().putXYZRAI(rgbImage(sizeX, sizeY)).defineMetaData(
			"Image").defineWriteOptions().tileSize(1024, 1024).nResolutionLevels(
				nResolutions).uncompressed().compressTemporaryFiles(false).savePath(file
					.getAbsolutePath()).create().export();

		long rawBytes = 3 * pyramidPixels(sizeX, sizeY, nResolutions);
		assertTrue("RGB export: " + pixelBytes(file) + " pixel bytes written for " +
			rawBytes + " raw bytes", pixelBytes(file) < 1.2 * rawBytes);

		ImageReader reader = new ImageReader();
		try {
			reader.setFlattenedResolutions(false);
			reader.setId(file.getAbsolutePath());
			reader.setSeries(0);
			reader.setResolution(0);
			assertTrue("The image should be RGB", reader.isRGB());
			byte[] plane = reader.openBytes(0);
			// The samples of a RGB plane can be stored interleaved (RGBRGB...) or
			// channel after channel (RR...GG...BB...)
			boolean interleaved = reader.isInterleaved();
			int stride = interleaved ? 3 : 1;
			int offset = interleaved ? 1 : sizeX * sizeY;
			for (int y = 0; y < sizeY; y++) {
				for (int x = 0; x < sizeX; x++) {
					int index = stride * (y * sizeX + x);
					assertEquals("Red at (" + x + ", " + y + ")", value(x, y),
						plane[index] & 0xFF);
					assertEquals("Green at (" + x + ", " + y + ")", value(x, y + 1),
						plane[index + offset] & 0xFF);
					assertEquals("Blue at (" + x + ", " + y + ")", value(x + 1, y),
						plane[index + 2 * offset] & 0xFF);
				}
			}
		}
		finally {
			reader.close();
		}
	}

	/**
	 * A tile size which is not a multiple of 16 is rounded by the TIFF writer:
	 * the exporter has to take this rounding into account, otherwise the number
	 * of tiles it computes does not match the number of tiles the writer expects,
	 * and the export never ends.
	 */
	@Test(timeout = 120000)
	public void tileSizeNotMultipleOf16IsSupported() throws Exception {
		int sizeX = 1000, sizeY = 500;
		File file = export(grayImage(sizeX, sizeY), "tile500", 500, 2, true);
		ImageReader reader = new ImageReader();
		try {
			reader.setId(file.getAbsolutePath());
			assertEquals(sizeX, reader.getSizeX());
			assertEquals(sizeY, reader.getSizeY());
		}
		finally {
			reader.close();
		}
	}

	/** The temporary files used to build the pyramid should be deleted */
	@Test
	public void temporaryFilesAreDeleted() throws Exception {
		export(grayImage(300, 200), "temp", 512, 3, true);
		File[] files = folder.getRoot().listFiles();
		assertEquals("Remaining files: " + java.util.Arrays.toString(files), 1,
			files.length);
	}
}
