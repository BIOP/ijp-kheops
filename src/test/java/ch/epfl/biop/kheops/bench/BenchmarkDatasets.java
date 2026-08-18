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

import ch.epfl.biop.DatasetHelper;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.function.Function;

/**
 * Real world datasets used by the benchmarks of the OME-TIFF export.
 * <p>
 * The files are downloaded once and cached by {@link DatasetHelper} in
 * {@code ~/CachedSamples} - they are never stored in this repository. Run the
 * {@link #main(String[])} method of this class to fetch everything up front
 * (roughly 1.8 GB), so that the benchmarks themselves do not measure the
 * download.
 * <p>
 * The datasets were picked to be representative of what Kheops is actually used
 * for, and to stress different parts of the export:
 * <ul>
 * <li>{@link #brainSlideVSI()}: a slide scanner file with many series of
 * multichannel 16 bits images - the archetypal Kheops input.</li>
 * <li>{@link #mouseBrainCZI()}: a large CZI, a format whose decoding is
 * expensive, which is what makes the source reader pool size matter.</li>
 * <li>{@link #melSTRLIF()}: a LIF with several series, 16 bits.</li>
 * </ul>
 * Synthetic images, which do not need any download and whose decoding is
 * almost free, are built by {@link SyntheticImages} - comparing them with the
 * files above is what separates a decoding bottleneck from a writing one.
 */
public class BenchmarkDatasets {

	/** Where the generated synthetic datasets are cached */
	public static final File BENCH_DIR = new File(DatasetHelper.cachedSampleDir,
		"kheops-bench");

	// Zenodo record 6553641 - mouse brain slices, Olympus VSI slide scanner
	// 22 fluorescent series of about 7000x6000 pixels, 3 channels, 16 bits
	public static final int BRAIN_SLIDE_INDEX = 0;

	// Zenodo record 19047136 - light sheet acquisition, Zeiss CZI, 470 MB
	public static final String CZI_MOUSE_BRAIN =
		"https://zenodo.org/records/19047136/files/" +
			"MouseBrain_41Slices_1Tile_1Channel_2Illuminations_2Angles.czi";

	// Zenodo record 13773035 - Leica LIF, 248 MB
	public static final String LIF_MELSTR =
		"https://zenodo.org/records/13773035/files/" +
			"Fig%206A%20MelSTR%20shCTL%2030%20min%20CEP%20GM_1.lif";

	/**
	 * Zenodo download links carry a {@code ?download=1} query which has to be
	 * removed before the URL is turned into a file name, otherwise the cached
	 * file name is invalid on Windows.
	 */
	private static final Function<String, String> ZENODO_DECODER = path -> {
		int query = path.indexOf('?');
		String withoutQuery = query < 0 ? path : path.substring(0, query);
		try {
			return URLDecoder.decode(withoutQuery, "UTF-8");
		}
		catch (UnsupportedEncodingException e) {
			return withoutQuery;
		}
	};

	/**
	 * @return an Olympus VSI slide, with 22 fluorescent series of about
	 *         7000x6000 pixels, 3 channels, 16 bits, and a few RGB overview
	 *         series
	 */
	public static File brainSlideVSI() throws IOException {
		String dir = DatasetHelper.dowloadBrainVSIDataset(BRAIN_SLIDE_INDEX);
		return new File(dir, "Slide_0" + BRAIN_SLIDE_INDEX + ".vsi");
	}

	/** @return a large Zeiss CZI */
	public static File mouseBrainCZI() {
		return DatasetHelper.getDataset(CZI_MOUSE_BRAIN + "?download=1",
			ZENODO_DECODER);
	}

	/** @return a Leica LIF */
	public static File melSTRLIF() {
		return DatasetHelper.getDataset(LIF_MELSTR + "?download=1",
			ZENODO_DECODER);
	}

	/** Downloads all the datasets used by the benchmarks */
	public static void main(String... args) throws Exception {
		System.out.println("Caching benchmark datasets in " +
			DatasetHelper.cachedSampleDir);
		report("VSI brain slide", brainSlideVSI());
		report("CZI mouse brain", mouseBrainCZI());
		report("LIF MelSTR", melSTRLIF());
	}

	private static void report(String name, File file) {
		System.out.printf("  %-16s %8.1f MB  %s%n", name, file.length() / (1024d *
			1024d), file);
	}
}
