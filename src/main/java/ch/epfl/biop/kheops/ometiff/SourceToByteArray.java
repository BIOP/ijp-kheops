/*-
 * #%L
 * Commands and function for opening, conversion and easy use of bioformats format into BigDataViewer
 * %%
 * Copyright (C) 2019 - 2022 Nicolas Chiaruttini, BIOP, EPFL
 * %%
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the BIOP nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package ch.epfl.biop.kheops.ometiff;

import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.GenericByteType;
import net.imglib2.type.numeric.integer.GenericShortType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.Views;

public class SourceToByteArray {

	public static <T> byte[] raiToByteArray(RandomAccessibleInterval<T> rai,
		T pixelInstance)
	{
		long nBytes = rai.dimension(0);
		for (int d = 1; d < rai.numDimensions(); d++) {
			nBytes *= rai.dimension(d);
		}
		if (pixelInstance instanceof GenericByteType) {
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
		else if (pixelInstance instanceof GenericShortType) {
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
