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

import ch.epfl.biop.kheops.ometiff.AverageImageScaler;

import java.util.List;
import java.util.Random;

/**
 * Measures {@link AverageImageScaler#downsample}, which builds every tile of
 * every resolution level above the first one.
 * <p>
 * The measured call is the one the exporter makes: a source region of
 * {@code 2 * tile} pixels is reduced to one tile, big endian samples, one
 * channel for grayscale images and three planar channels for RGB ones. The
 * default tile size of the Kheops command is 1024, so the source region is
 * 2048x2048.
 * <p>
 * Run with:
 *
 * <pre>
 * mvn -Denforcer.skip=true test-compile
 * java -cp &lt;test classpath&gt; ch.epfl.biop.kheops.bench.ScalerBenchmark
 * </pre>
 */
public class ScalerBenchmark {

	private static final int TILE = 1024;
	private static final int SCALE = 2;
	private static final int WARMUP = 3;
	private static final int REPEATS = 7;
	/** Number of tiles downsampled per measured run */
	private static final int TILES_PER_RUN = 20;

	public static void main(String... args) throws Exception {
		Bench.printEnvironment();
		System.out.println("Downsampling " + TILES_PER_RUN + " source regions of " +
			(SCALE * TILE) + "x" + (SCALE * TILE) + " by " + SCALE + "\n");

		List<Bench.Result> results = Bench.results();
		results.add(measure("uint8, 1 channel", 1, false, 1, false));
		results.add(measure("uint16, 1 channel", 2, false, 1, false));
		results.add(measure("float32, 1 channel", 4, true, 1, false));
		results.add(measure("uint8, 3 channels planar", 1, false, 3, false));
		results.add(measure("uint8, 3 channels interleaved", 1, false, 3, true));

		// Each line has a different pixel type, so comparing them makes no sense
		Bench.report("AverageImageScaler.downsample, tile " + TILE, results, false);
	}

	private static Bench.Result measure(String name, int bytesPerPixel,
		boolean floatingPoint, int channels, boolean interleaved) throws Exception
	{
		int width = SCALE * TILE, height = SCALE * TILE;
		byte[] source = randomSamples(width * height * channels, bytesPerPixel,
			floatingPoint);
		double sourceMB = (double) source.length / (1024 * 1024);
		AverageImageScaler scaler = new AverageImageScaler();
		return Bench.measure(name, WARMUP, REPEATS, TILES_PER_RUN * sourceMB, () -> {
			for (int i = 0; i < TILES_PER_RUN; i++) {
				Bench.consume(scaler.downsample(source, width, height, SCALE,
					bytesPerPixel, false, floatingPoint, channels, interleaved));
			}
		});
	}

	/**
	 * @return plausible big endian samples - random bytes would give NaN and
	 *         infinite floats, whose arithmetic is not representative
	 */
	private static byte[] randomSamples(int nSamples, int bytesPerPixel,
		boolean floatingPoint)
	{
		byte[] bytes = new byte[nSamples * bytesPerPixel];
		Random random = new Random(1234);
		for (int s = 0; s < nSamples; s++) {
			int bits = floatingPoint ? Float.floatToIntBits(random.nextFloat() * 1000)
				: random.nextInt(1 << (8 * Math.min(bytesPerPixel, 2)));
			for (int b = 0; b < bytesPerPixel; b++) {
				bytes[s * bytesPerPixel + b] = (byte) (bits >>> (8 * (bytesPerPixel - 1 -
					b)));
			}
		}
		return bytes;
	}
}
