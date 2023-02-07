/*-
 * #%L
 * IJ2 commands that use bio-formats to create pyramidal ome.tiff
 * %%
 * Copyright (C) 2018 - 2023 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP)
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

import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

import java.util.HashSet;
import java.util.Set;

/**
 * The core of the conversion from Imglib2 types to byte arrays.
 */
public class SourceToByteArray {

	/**
	 * Supported pixel type:
	 * - {@link UnsignedByteType}
	 * - {@link UnsignedShortType}
	 * - {@link ARGBType}
	 * - {@link FloatType}
	 * @param t pixel instance
	 * @return is the pixel type can be exported or not in {@link OMETiffExporter}
	 * @param <T> pixel type class
	 */
	public static <T> boolean validPixelType(T t) {
		Set<Class<? extends Type<?>>> validClasses = new HashSet<>();
		validClasses.add(UnsignedByteType.class);
		validClasses.add(UnsignedShortType.class);
		validClasses.add(ARGBType.class);
		//validClasses.add(FloatType.class); // not done yet
		return validClasses.contains(t.getClass());
	}

	public static <T> byte[] raiToByteArray(RandomAccessibleInterval<T> rai,
		T pixelInstance)
	{
		long nBytes = rai.dimension(0);
		for (int d = 1; d < rai.numDimensions(); d++) {
			nBytes *= rai.dimension(d);
		}
		if (pixelInstance instanceof UnsignedByteType) {
			Cursor<UnsignedByteType> c = (Cursor<UnsignedByteType>) Views
				.flatIterable(rai).cursor();

			nBytes *= 1; // Byte

			if (nBytes > Integer.MAX_VALUE) {
				System.err.println("Too many bytes during export!");
				return null;
			}

			byte[] out = new byte[(int) nBytes];

			for (int i = 0; i < nBytes; i++) {
				out[i] = c.next().getByte();
			}
			return out;
		}
		else if (pixelInstance instanceof UnsignedShortType) {
			Cursor<UnsignedShortType> c = (Cursor<UnsignedShortType>) Views
				.flatIterable(rai).cursor();

			nBytes *= 2; // Short

			if (nBytes > Integer.MAX_VALUE) {
				System.err.println("Too many bytes during export!");
				return null;
			}

			byte[] out = new byte[(int) nBytes];

			for (int i = 0; i < nBytes; i += 2) {
				int value = c.next().get();
				out[i] = (byte) (value >>> 8);
				out[i + 1] = (byte) value;
			}
			return out;
		}
		else if (pixelInstance instanceof ARGBType) {
			Cursor<ARGBType> c = (Cursor<ARGBType>) Views.flatIterable(rai).cursor();

			nBytes *= 3; // ARGB, discarding A

			if (nBytes > Integer.MAX_VALUE) {
				System.err.println("Too many bytes during export!");
				return null;
			}

			byte[] out = new byte[(int) nBytes];

			for (int i = 0; i < nBytes; i += 3) {
				int value = c.next().get();
				out[i] = (byte) (value >>> 16);
				out[i + 1] = (byte) (value >>> 8);
				out[i + 2] = (byte) value;
			}
			return out;
		}
		else {
			throw new UnsupportedOperationException(
				"Unsupported pixel type of class " + pixelInstance.getClass()
					.getName());
		}
	}
}
