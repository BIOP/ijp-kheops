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

import loci.common.DataTools;
import loci.common.DebugTools;
import loci.common.RandomAccessInputStream;
import loci.formats.FormatTools;
import loci.formats.ImageReader;
import loci.formats.tiff.IFD;
import loci.formats.tiff.TiffParser;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests of the OME-TIFF export.
 * <p>
 * A first set of tests focuses on the size of the exported file: the tiles of a
 * TIFF file are always fully written, even when they extend beyond the image
 * boundaries. The exporter thus has to adapt the tile size to each resolution
 * level, otherwise a lot of padding pixels are written, see
 * <a href="https://github.com/BIOP/ijp-kheops/issues/22">issue #22</a>.
 * <p>
 * A second set checks that the exported pixels are the expected ones, for every
 * supported pixel type and for images which have several channels, z slices and
 * timepoints.
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

	// ------------------------------- 16 bits, floats, several C, Z and T

	/** @return the value of the 16 bits synthetic image at the given position */
	private static int value16(int x, int y) {
		return (x * 31 + y * 257) % 65003;
	}

	private static RandomAccessibleInterval<UnsignedShortType> gray16Image(
		int sizeX, int sizeY)
	{
		FunctionRandomAccessible<UnsignedShortType> fn =
			new FunctionRandomAccessible<>(2, (position, pixel) -> pixel.set(value16(
				position.getIntPosition(0), position.getIntPosition(1))),
				UnsignedShortType::new);
		return Views.interval(fn, new FinalInterval(new long[] { 0, 0 }, new long[] {
			sizeX - 1, sizeY - 1 }));
	}

	/**
	 * @return the value of the float synthetic image: a multiple of 1/8, so that
	 *         the average of a 2x2 block is exactly representable
	 */
	private static float valueFloat(int x, int y) {
		return ((x * 3 + y * 7) % 251) / 8f;
	}

	private static RandomAccessibleInterval<FloatType> floatImage(int sizeX,
		int sizeY)
	{
		FunctionRandomAccessible<FloatType> fn = new FunctionRandomAccessible<>(2, (
			position, pixel) -> pixel.set(valueFloat(position.getIntPosition(0),
				position.getIntPosition(1))), FloatType::new);
		return Views.interval(fn, new FinalInterval(new long[] { 0, 0 }, new long[] {
			sizeX - 1, sizeY - 1 }));
	}

	/** @return the value of a multidimensional 16 bits synthetic image */
	private static int valueCZT(int x, int y, int c, int z, int t) {
		return (value16(x, y) + 12345 * c + 4321 * z + 777 * t) % 65003;
	}

	private static RandomAccessibleInterval<UnsignedShortType> cztImage(int sizeX,
		int sizeY, int sizeZ, int c, int t)
	{
		FunctionRandomAccessible<UnsignedShortType> fn =
			new FunctionRandomAccessible<>(3, (position, pixel) -> pixel.set(valueCZT(
				position.getIntPosition(0), position.getIntPosition(1), c, position
					.getIntPosition(2), t)), UnsignedShortType::new);
		return Views.interval(fn, new FinalInterval(new long[] { 0, 0, 0 },
			new long[] { sizeX - 1, sizeY - 1, sizeZ - 1 }));
	}

	/**
	 * @return the samples of a plane, decoded according to the pixel type and
	 *         the endianness the reader reports
	 */
	private static double[] readPlane(ImageReader reader, int plane)
		throws Exception
	{
		byte[] bytes = reader.openBytes(plane);
		int type = reader.getPixelType();
		int bytesPerPixel = FormatTools.getBytesPerPixel(type);
		boolean little = reader.isLittleEndian();
		boolean floatingPoint = FormatTools.isFloatingPoint(type);
		double[] values = new double[bytes.length / bytesPerPixel];
		for (int i = 0; i < values.length; i++) {
			int offset = i * bytesPerPixel;
			values[i] = floatingPoint ? DataTools.bytesToFloat(bytes, offset,
				bytesPerPixel, little) : DataTools.bytesToInt(bytes, offset,
					bytesPerPixel, little);
		}
		return values;
	}

	/** @return a reader positioned on the given resolution level of a file */
	private static ImageReader open(File file, int resolution) throws Exception {
		ImageReader reader = new ImageReader();
		reader.setFlattenedResolutions(false);
		reader.setId(file.getAbsolutePath());
		reader.setSeries(0);
		reader.setResolution(resolution);
		return reader;
	}

	/** 16 bits is the most common Kheops input: pixels should be untouched */
	@Test
	public void uint16PixelsAreUnchanged() throws Exception {
		int sizeX = 613, sizeY = 227;
		File file = export(gray16Image(sizeX, sizeY), "uint16", 512, 3, true);
		ImageReader reader = open(file, 0);
		try {
			assertEquals(FormatTools.UINT16, reader.getPixelType());
			assertEquals(sizeX, reader.getSizeX());
			assertEquals(sizeY, reader.getSizeY());
			double[] plane = readPlane(reader, 0);
			for (int y = 0; y < sizeY; y++) {
				for (int x = 0; x < sizeX; x++) {
					assertEquals("pixel (" + x + ", " + y + ")", value16(x, y), plane[y *
						sizeX + x], 0);
				}
			}
		}
		finally {
			reader.close();
		}
	}

	/** The 16 bits pyramid levels should hold the average of the level above */
	@Test
	public void uint16PyramidLevelsAreDownsampled() throws Exception {
		int sizeX = 613, sizeY = 227;
		File file = export(gray16Image(sizeX, sizeY), "uint16pyr", 128, 3, true);
		ImageReader reader = open(file, 1);
		try {
			int width = reader.getSizeX();
			int height = reader.getSizeY();
			double[] plane = readPlane(reader, 0);
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					int expected = (value16(2 * x, 2 * y) + value16(2 * x + 1, 2 * y) +
						value16(2 * x, 2 * y + 1) + value16(2 * x + 1, 2 * y + 1)) / 4;
					assertEquals("downsampled pixel (" + x + ", " + y + ")", expected,
						plane[y * width + x], 1);
				}
			}
		}
		finally {
			reader.close();
		}
	}

	/** Float pixels should be exported untouched */
	@Test
	public void floatPixelsAreUnchanged() throws Exception {
		int sizeX = 613, sizeY = 227;
		File file = export(floatImage(sizeX, sizeY), "float", 512, 3, true);
		ImageReader reader = open(file, 0);
		try {
			assertEquals(FormatTools.FLOAT, reader.getPixelType());
			double[] plane = readPlane(reader, 0);
			for (int y = 0; y < sizeY; y++) {
				for (int x = 0; x < sizeX; x++) {
					assertEquals("pixel (" + x + ", " + y + ")", valueFloat(x, y), plane[y *
						sizeX + x], 0);
				}
			}
		}
		finally {
			reader.close();
		}
	}

	/**
	 * The float pyramid levels should hold the average of the level above - the
	 * fractional part of the samples has to be preserved.
	 */
	@Test
	public void floatPyramidLevelsAreDownsampled() throws Exception {
		int sizeX = 612, sizeY = 226;
		File file = export(floatImage(sizeX, sizeY), "floatpyr", 128, 3, true);
		ImageReader reader = open(file, 1);
		try {
			int width = reader.getSizeX();
			int height = reader.getSizeY();
			double[] plane = readPlane(reader, 0);
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					float expected = (valueFloat(2 * x, 2 * y) + valueFloat(2 * x + 1, 2 *
						y) + valueFloat(2 * x, 2 * y + 1) + valueFloat(2 * x + 1, 2 * y + 1)) /
						4f;
					assertEquals("downsampled pixel (" + x + ", " + y + ")", expected,
						plane[y * width + x], 1e-4);
				}
			}
		}
		finally {
			reader.close();
		}
	}

	/** The downsampled levels of an RGB image should hold averages too */
	@Test
	public void rgbPyramidLevelsAreDownsampled() throws Exception {
		int sizeX = 256, sizeY = 128;
		File file = new File(folder.getRoot(), "rgbpyr.ome.tiff");
		OMETiffExporter.builder().putXYZRAI(rgbImage(sizeX, sizeY)).defineMetaData(
			"Image").defineWriteOptions().tileSize(64, 64).nResolutionLevels(2)
			.uncompressed().compressTemporaryFiles(false).savePath(file
				.getAbsolutePath()).create().export();

		ImageReader reader = open(file, 1);
		try {
			int width = reader.getSizeX();
			int height = reader.getSizeY();
			byte[] plane = reader.openBytes(0);
			boolean interleaved = reader.isInterleaved();
			int stride = interleaved ? 3 : 1;
			int offset = interleaved ? 1 : width * height;
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					int index = stride * (y * width + x);
					int red = 0, green = 0, blue = 0;
					for (int dy = 0; dy < 2; dy++) {
						for (int dx = 0; dx < 2; dx++) {
							red += value(2 * x + dx, 2 * y + dy);
							green += value(2 * x + dx, 2 * y + dy + 1);
							blue += value(2 * x + dx + 1, 2 * y + dy);
						}
					}
					assertEquals("red (" + x + ", " + y + ")", red / 4, plane[index] & 0xFF,
						1);
					assertEquals("green (" + x + ", " + y + ")", green / 4, plane[index +
						offset] & 0xFF, 1);
					assertEquals("blue (" + x + ", " + y + ")", blue / 4, plane[index + 2 *
						offset] & 0xFF, 1);
				}
			}
		}
		finally {
			reader.close();
		}
	}

	/**
	 * Several channels, z slices and timepoints: every plane should hold its own
	 * data, which checks the order in which the planes are written.
	 */
	@Test
	public void multiChannelSliceAndTimepointExportIsValid() throws Exception {
		int sizeX = 130, sizeY = 90, sizeZ = 3, sizeC = 2, sizeT = 2;
		File file = new File(folder.getRoot(), "czt.ome.tiff");
		@SuppressWarnings("rawtypes")
		OMETiffExporter.OMETiffExporterBuilder.Data.DataBuilder data =
			OMETiffExporter.builder();
		for (int c = 0; c < sizeC; c++) {
			for (int t = 0; t < sizeT; t++) {
				data.putXYZRAI(c, t, (RandomAccessibleInterval) cztImage(sizeX, sizeY,
					sizeZ, c, t));
			}
		}
		data.defineMetaData("Image").defineWriteOptions().tileSize(64, 64)
			.nResolutionLevels(2).uncompressed().compressTemporaryFiles(false).savePath(
				file.getAbsolutePath()).create().export();

		ImageReader reader = open(file, 0);
		try {
			assertEquals("channels", sizeC, reader.getSizeC());
			assertEquals("z slices", sizeZ, reader.getSizeZ());
			assertEquals("timepoints", sizeT, reader.getSizeT());
			for (int t = 0; t < sizeT; t++) {
				for (int c = 0; c < sizeC; c++) {
					for (int z = 0; z < sizeZ; z++) {
						double[] plane = readPlane(reader, reader.getIndex(z, c, t));
						for (int y = 0; y < sizeY; y++) {
							for (int x = 0; x < sizeX; x++) {
								assertEquals("pixel (" + x + ", " + y + ") of c" + c + " z" + z +
									" t" + t, valueCZT(x, y, c, z, t), plane[y * sizeX + x], 0);
							}
						}
					}
				}
			}
		}
		finally {
			reader.close();
		}
	}

	// ---------------------------------------------------------------- no tiling

	/**
	 * @return an exporter of a multidimensional 16 bits image - a non-positive
	 *         tile size means that the image should not be tiled
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private OMETiffExporter<?> cztExporter(String name, int sizeX, int sizeY,
		int sizeZ, int sizeC, int sizeT, int tileSize, int nResolutions)
		throws Exception
	{
		File file = new File(folder.getRoot(), name + ".ome.tiff");
		OMETiffExporter.OMETiffExporterBuilder.Data.DataBuilder data =
			OMETiffExporter.builder();
		for (int c = 0; c < sizeC; c++) {
			for (int t = 0; t < sizeT; t++) {
				data.putXYZRAI(c, t, (RandomAccessibleInterval) cztImage(sizeX, sizeY,
					sizeZ, c, t));
			}
		}
		return data.defineMetaData("Image").defineWriteOptions().tileSize(tileSize,
			tileSize).nResolutionLevels(nResolutions).uncompressed()
			.compressTemporaryFiles(false).savePath(file.getAbsolutePath()).create();
	}

	/**
	 * A non-positive tile size means that the user does not want any tiling: a
	 * whole plane is then written at once, so the number of blocks reported to the
	 * task service should be counted in planes, see
	 * <a href="https://github.com/BIOP/ijp-kheops/issues/31">issue #31</a>.
	 * <p>
	 * {@code totalTiles} is the value given to
	 * {@link org.scijava.task.Task#setProgressMaximum(long)}, and the progress is
	 * incremented once per written block.
	 */
	@Test(timeout = 120000)
	public void blocksAreCountedInPlanesWhenTilingIsDisabled() throws Exception {
		int sizeX = 613, sizeY = 227, sizeZ = 3, sizeC = 2, sizeT = 2;
		int nResolutions = 3;
		OMETiffExporter<?> exporter = cztExporter("notiled", sizeX, sizeY, sizeZ,
			sizeC, sizeT, -1, nResolutions);
		long planes = (long) sizeZ * sizeC * sizeT;
		exporter.export();
		assertEquals("One block per plane and per resolution level", planes *
			nResolutions, exporter.totalTiles);
		for (int r = 0; r < nResolutions; r++) {
			assertEquals("Blocks along x of resolution level " + r, 1, (int) exporter.resToNX
				.get(r));
			assertEquals("Blocks along y of resolution level " + r, 1, (int) exporter.resToNY
				.get(r));
		}
	}

	/** With tiling, a plane is still written as several blocks */
	@Test(timeout = 120000)
	public void blocksAreCountedInTilesWhenTilingIsEnabled() throws Exception {
		int sizeX = 613, sizeY = 227, sizeZ = 2, sizeC = 2, sizeT = 1;
		int nResolutions = 3;
		OMETiffExporter<?> exporter = cztExporter("tiled", sizeX, sizeY, sizeZ,
			sizeC, sizeT, 128, nResolutions);
		long planes = (long) sizeZ * sizeC * sizeT;
		exporter.export();
		long expected = 0;
		for (int r = 0; r < nResolutions; r++) {
			expected += (long) exporter.resToNX.get(r) * exporter.resToNY.get(r);
		}
		assertTrue("A tiled export should have more blocks than planes", expected >
			nResolutions);
		assertEquals("One block per tile, plane and resolution level", planes *
			expected, exporter.totalTiles);
	}

	/** Without tiling, the file should hold plain strips and the same pixels */
	@Test(timeout = 120000)
	public void untiledExportIsValid() throws Exception {
		int sizeX = 613, sizeY = 227, nResolutions = 2;
		File file = export(grayImage(sizeX, sizeY), "untiled", -1, nResolutions,
			true);
		try (RandomAccessInputStream in = new RandomAccessInputStream(file
			.getAbsolutePath()))
		{
			List<IFD> ifds = new TiffParser(in).getIFDs();
			assertEquals(nResolutions, ifds.size());
			for (IFD ifd : ifds) {
				assertFalse("The image should not be tiled", ifd.containsKey(
					IFD.TILE_WIDTH));
			}
		}
		// Without tiling, there is no padding pixel at all
		assertEquals(pyramidPixels(sizeX, sizeY, nResolutions), pixelBytes(file));

		ImageReader reader = open(file, 0);
		try {
			assertEquals(sizeX, reader.getSizeX());
			assertEquals(sizeY, reader.getSizeY());
			byte[] plane = reader.openBytes(0);
			for (int y = 0; y < sizeY; y++) {
				for (int x = 0; x < sizeX; x++) {
					assertEquals("pixel (" + x + ", " + y + ")", value(x, y), plane[y *
						sizeX + x] & 0xFF);
				}
			}
		}
		finally {
			reader.close();
		}
	}
}
