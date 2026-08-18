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

import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests of {@link SourceToByteArray}, which turns the tile of an ImgLib2 image
 * into the big endian byte array the TIFF writer expects.
 * <p>
 * The tiles handed over to this class are cropped out of a bigger image, so
 * their minimum is usually not the origin - this is what most of these tests
 * check.
 */
public class SourceToByteArrayTest {

	// ---------------------------------------------------------- test images

	/** @return a value which depends on the position, in [0, 65536[ */
	private static int value(int x, int y) {
		return (x * 7919 + y * 104729) % 65536;
	}

	private static RandomAccessibleInterval<UnsignedByteType> bytes(int sizeX,
		int sizeY)
	{
		return Views.interval(new FunctionRandomAccessible<>(2, (position,
			pixel) -> pixel.set(value(position.getIntPosition(0), position
				.getIntPosition(1)) & 0xFF), UnsignedByteType::new), new FinalInterval(
					sizeX, sizeY));
	}

	private static RandomAccessibleInterval<UnsignedShortType> shorts(int sizeX,
		int sizeY)
	{
		return Views.interval(new FunctionRandomAccessible<>(2, (position,
			pixel) -> pixel.set(value(position.getIntPosition(0), position
				.getIntPosition(1))), UnsignedShortType::new), new FinalInterval(sizeX,
					sizeY));
	}

	private static RandomAccessibleInterval<FloatType> floats(int sizeX,
		int sizeY)
	{
		return Views.interval(new FunctionRandomAccessible<>(2, (position,
			pixel) -> pixel.set(value(position.getIntPosition(0), position
				.getIntPosition(1)) / 8f), FloatType::new), new FinalInterval(sizeX,
					sizeY));
	}

	private static RandomAccessibleInterval<ARGBType> argb(int sizeX, int sizeY) {
		return Views.interval(new FunctionRandomAccessible<>(2, (position,
			pixel) -> {
			int x = position.getIntPosition(0);
			int y = position.getIntPosition(1);
			pixel.set(ARGBType.rgba(value(x, y) & 0xFF, value(x, y + 1) & 0xFF, value(x +
				1, y) & 0xFF, 128));
		}, ARGBType::new), new FinalInterval(sizeX, sizeY));
	}

	/** @return the tile [minX, minX + width[ x [minY, minY + height[ */
	private static <T> RandomAccessibleInterval<T> tile(
		RandomAccessibleInterval<T> image, int minX, int minY, int width, int height)
	{
		return Views.interval(image, new FinalInterval(new long[] { minX, minY },
			new long[] { minX + width - 1, minY + height - 1 }));
	}

	// ------------------------------------------------------------------ tests

	@Test
	public void supportedPixelTypes() {
		assertTrue(SourceToByteArray.validPixelType(new UnsignedByteType()));
		assertTrue(SourceToByteArray.validPixelType(new UnsignedShortType()));
		assertTrue(SourceToByteArray.validPixelType(new FloatType()));
		assertTrue(SourceToByteArray.validPixelType(new ARGBType()));
		assertFalse(SourceToByteArray.validPixelType(new net.imglib2.type.numeric.integer.IntType()));
	}

	/** 8 bits samples are written as is, row by row */
	@Test
	public void byteTile() {
		int minX = 37, minY = 61, width = 48, height = 16;
		byte[] out = SourceToByteArray.raiToByteArray(tile(bytes(256, 256), minX,
			minY, width, height), new UnsignedByteType());
		assertEquals(width * height, out.length);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				assertEquals("pixel (" + x + ", " + y + ")", value(minX + x, minY + y) &
					0xFF, out[y * width + x] & 0xFF);
			}
		}
	}

	/** 16 bits samples are written big endian */
	@Test
	public void shortTileIsBigEndian() {
		int minX = 37, minY = 61, width = 48, height = 16;
		byte[] out = SourceToByteArray.raiToByteArray(tile(shorts(256, 256), minX,
			minY, width, height), new UnsignedShortType());
		assertEquals(2 * width * height, out.length);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int offset = 2 * (y * width + x);
				int actual = ((out[offset] & 0xFF) << 8) | (out[offset + 1] & 0xFF);
				assertEquals("pixel (" + x + ", " + y + ")", value(minX + x, minY + y),
					actual);
			}
		}
	}

	/** 32 bits floats are written big endian */
	@Test
	public void floatTileIsBigEndian() {
		int minX = 37, minY = 61, width = 48, height = 16;
		byte[] out = SourceToByteArray.raiToByteArray(tile(floats(256, 256), minX,
			minY, width, height), new FloatType());
		assertEquals(4 * width * height, out.length);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int offset = 4 * (y * width + x);
				int bits = 0;
				for (int b = 0; b < 4; b++) {
					bits = (bits << 8) | (out[offset + b] & 0xFF);
				}
				assertEquals("pixel (" + x + ", " + y + ")", value(minX + x, minY + y) /
					8f, Float.intBitsToFloat(bits), 0f);
			}
		}
	}

	/** ARGB pixels are written interleaved, and the alpha channel is dropped */
	@Test
	public void argbTileIsInterleavedRGB() {
		int minX = 37, minY = 61, width = 48, height = 16;
		byte[] out = SourceToByteArray.raiToByteArray(tile(argb(256, 256), minX,
			minY, width, height), new ARGBType());
		assertEquals(3 * width * height, out.length);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int offset = 3 * (y * width + x);
				int sx = minX + x, sy = minY + y;
				assertEquals("red (" + x + ", " + y + ")", value(sx, sy) & 0xFF,
					out[offset] & 0xFF);
				assertEquals("green (" + x + ", " + y + ")", value(sx, sy + 1) & 0xFF,
					out[offset + 1] & 0xFF);
				assertEquals("blue (" + x + ", " + y + ")", value(sx + 1, sy) & 0xFF,
					out[offset + 2] & 0xFF);
			}
		}
	}

	/** The tiles of the last column and row of an image are cropped */
	@Test
	public void croppedTile() {
		int width = 13, height = 7;
		byte[] out = SourceToByteArray.raiToByteArray(tile(shorts(256, 256), 250,
			250, 6, 6), new UnsignedShortType());
		assertEquals(2 * 6 * 6, out.length);
		byte[] small = SourceToByteArray.raiToByteArray(tile(bytes(256, 256), 0, 0,
			width, height), new UnsignedByteType());
		assertEquals(width * height, small.length);
	}

	/** A 3D image is sliced by the exporter before being converted */
	@Test
	public void hyperSliceOfA3DImage() {
		RandomAccessibleInterval<UnsignedShortType> image3D = Views.interval(
			new FunctionRandomAccessible<>(3, (position, pixel) -> pixel.set(value(
				position.getIntPosition(0), position.getIntPosition(1)) + position
					.getIntPosition(2)), UnsignedShortType::new), new FinalInterval(64, 64,
						4));
		int z = 2;
		byte[] out = SourceToByteArray.raiToByteArray(tile(Views.hyperSlice(image3D,
			2, z), 16, 16, 8, 8), new UnsignedShortType());
		assertEquals(2 * 8 * 8, out.length);
		for (int y = 0; y < 8; y++) {
			for (int x = 0; x < 8; x++) {
				int offset = 2 * (y * 8 + x);
				int actual = ((out[offset] & 0xFF) << 8) | (out[offset + 1] & 0xFF);
				assertEquals("pixel (" + x + ", " + y + ", " + z + ")", value(16 + x, 16 +
					y) + z, actual);
			}
		}
	}
}
