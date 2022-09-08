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

import loci.common.image.IImageScaler;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * Implementation of {@link IImageScaler}, where an averaging is made. A n-by-n
 * source region is sampled to 1 output pixel by averaging the n-by-n pixels.
 * Many types of pixels unsupported TODO : check RGB, and support other types
 */

public class AverageImageScaler implements IImageScaler {

	/**
	 * @see IImageScaler#downsample(byte[], int, int, double, int, boolean,
	 *      boolean, int, boolean)
	 */
	@Override
	public byte[] downsample(final byte[] srcImage, final int width,
		final int height, final double scaleFactor, final int bytesPerPixel,
		final boolean littleEndian, final boolean floatingPoint, final int channels,
		final boolean interleaved)
	{

		if (scaleFactor != (int) scaleFactor)
			throw new UnsupportedOperationException(
				"Unsupported non integer scale factor");

		int scaleInt = (int) scaleFactor;
		if (scaleInt == 1) return srcImage;

		if (scaleFactor < 1) {
			throw new IllegalArgumentException("Scale factor cannot be less than 1");
		}
		int newW = width / scaleInt;
		int newH = height / scaleInt;

		byte[][] allshifts = new byte[scaleInt * scaleInt][newW * newH *
			bytesPerPixel * channels];
		for (int ys = 0; ys < scaleInt; ys++) {
			for (int xs = 0; xs < scaleInt; xs++) {
				allshifts[ys * scaleInt + xs] = getBufferWithShift(srcImage, width,
					height, scaleInt, bytesPerPixel, littleEndian, floatingPoint,
					channels, interleaved, xs, ys);
			}
		}
		int nPix = newW * newH * channels;
		int nShifts = scaleInt * scaleInt;
		int offs = 0;

		if (bytesPerPixel == 1) {
			byte[] avg = new byte[newW * newH * bytesPerPixel * channels];
			for (int iPix = 0; iPix < nPix; iPix++) {
				int v = 0;
				for (int is = 0; is < nShifts; is++) {
					v += (((int) allshifts[is][offs]) & 0xff); // unsigned byte
				}
				avg[offs] = (byte) (v / nShifts);
				offs += bytesPerPixel;
			}
			return avg;
		}
		else if (bytesPerPixel == 2) {
			ByteBuffer buffer = ByteBuffer.allocate(nPix * 2);
			ShortBuffer bufferShort = buffer.asShortBuffer();
			for (int iPix = 0; iPix < nPix; iPix++) {
				int v = 0;
				for (int is = 0; is < nShifts; is++) {
					byte hi = allshifts[is][offs];
					byte lo = allshifts[is][offs + 1];
					v += (((hi & 0xFF) << 8) | (lo & 0xFF));
				}
				bufferShort.put((short) (v / nShifts));
				offs += bytesPerPixel;
			}
			return buffer.array();
		}
		else {
			throw new UnsupportedOperationException("Cannot handle pixel type with " +
				bytesPerPixel + " bytes per pixels. Please contribute!");
		}
	}

	private byte[] getBufferWithShift(byte[] srcImage, int width, int height,
		int scaleFactor, int bytesPerPixel, boolean littleEndian,
		boolean floatingPoint, int channels, boolean interleaved, int shiftX,
		int shiftY)
	{
		int newW = width / scaleFactor;
		int newH = height / scaleFactor;

		if (newW == width && newH == height) {
			return srcImage;
		}

		int yd = (height / newH) * width - width;
		int yr = height % newH;
		int xd = width / newW;
		int xr = width % newW;

		byte[] outBuf = new byte[newW * newH * bytesPerPixel * channels];
		int count = interleaved ? 1 : channels;
		int pixelChannels = interleaved ? channels : 1; // Either count or

		for (int c = 0; c < count; c++) {
			int srcOffset = c * width * height;
			srcOffset += shiftX + shiftY * width;
			int destOffset = c * newW * newH;
			for (int yyy = newH, ye = 0; yyy > 0; yyy--) {
				for (int xxx = newW, xe = 0; xxx > 0; xxx--) {
					// for every pixel in the output image, pick the upper-left-most pixel
					// in the corresponding area of the source image, e.g. for a scale
					// factor of 2.0:
					//
					// --------- -----
					// |a|b|c|d| |a|c|
					// --------- -----
					// |e|f|g|h| |i|k|
					// --------- ==> -----
					// |i|j|k|l|
					// ---------
					// |m|n|o|p|
					// ---------
					for (int rgb = 0; rgb < pixelChannels; rgb++) {
						for (int b = 0; b < bytesPerPixel; b++) {
							outBuf[bytesPerPixel * (destOffset * pixelChannels + rgb) + b] =
								srcImage[bytesPerPixel * (srcOffset * pixelChannels + rgb) + b];
						}
					}
					destOffset++;
					srcOffset += xd;
					xe += xr;
					if (xe >= newW) {
						xe -= newW;
						srcOffset++;
					}
				}
				srcOffset += yd;
				ye += yr;
				if (ye >= newH) {
					ye -= newH;
					srcOffset += width;
				}
			}
		}
		return outBuf;
	}

}
