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

import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.kheops.KheopsHelper;
import ch.epfl.biop.kheops.ometiff.OMETiffExporter;
import loci.common.DebugTools;
import net.imglib2.RandomAccessibleInterval;
import org.scijava.Context;
import org.scijava.task.TaskService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Compares the compression codecs the TIFF writer supports, on both the time
 * they take and the size of the file they produce.
 * <p>
 * The compression runs on the single thread which writes the tiles, so it is
 * directly on the critical path of the export: a codec which is both faster and
 * at least as effective as the default LZW would be a free improvement.
 * <p>
 * Run with:
 *
 * <pre>
 * java -Xmx12g -cp &lt;test classpath&gt; ch.epfl.biop.kheops.bench.CompressionBenchmark [vsi|synthetic]
 * </pre>
 */
public class CompressionBenchmark {

	private static final int TILE = 1024;
	private static final int WARMUP = 1;
	private static final int REPEATS = 3;
	private static final int READER_POOL = 4;
	private static final int THREADS = Math.max(1, Runtime.getRuntime()
		.availableProcessors() - 1);

	/** Codecs which make sense for lossless microscopy data */
	private static final String[] CODECS = { "LZW", "zlib", "Uncompressed" };

	private static final File OUTPUT_DIR = new File(System.getProperty(
		"java.io.tmpdir"), "kheops-bench-output");

	public static void main(String... args) throws Exception {
		DebugTools.setRootLevel("OFF");
		Bench.printEnvironment();
		if (!OUTPUT_DIR.exists() && !OUTPUT_DIR.mkdirs()) {
			throw new IllegalStateException("Could not create " + OUTPUT_DIR);
		}

		String dataset = args.length > 0 ? args[0].toLowerCase() : "vsi";
		Context context = new Context(TaskService.class);
		try {
			File input = "synthetic".equals(dataset) ? SyntheticImages.uint16File()
				: BenchmarkDatasets.brainSlideVSI();
			int series = "synthetic".equals(dataset) ? 0 : 2;
			run(dataset, input, series, context);
		}
		finally {
			context.dispose();
		}
	}

	private static void run(String title, File input, int series, Context context)
		throws Exception
	{
		System.out.println("\n### " + title + ", series " + series + "\n    " +
			input);
		List<Bench.Result> results = new ArrayList<>();
		for (String codec : CODECS) {
			long[] fileSize = new long[1];
			results.add(Bench.measure(codec, WARMUP, REPEATS, 0, () -> {
				File output = new File(OUTPUT_DIR, "compression.ome.tiff");
				if (output.exists() && !output.delete()) {
					throw new IllegalStateException("Could not delete " + output);
				}
				export(input, series, codec, output, context);
				fileSize[0] = output.length();
				if (!output.delete()) {
					System.err.println("Could not delete " + output);
				}
			}));
			System.out.printf("      -> %.0f MB written%n", fileSize[0] / (1024d *
				1024d));
		}
		Bench.report("Compression codecs, " + title, results);
	}

	@SuppressWarnings("rawtypes")
	private static void export(File input, int series, String codec, File output,
		Context context) throws Exception
	{
		KheopsHelper.SourcesInfo info = KheopsHelper.getSourcesFromFile(input
			.getAbsolutePath(), TILE, TILE, 64, READER_POOL, false, "CORNER", context);
		try {
			SourceAndConverter[] sources = info.idToSources.get(series).toArray(
				new SourceAndConverter[0]);
			RandomAccessibleInterval<?> model = sources[0].getSpimSource().getSource(0,
				0);
			int size = (int) Math.min(model.dimension(0), model.dimension(1));
			int levels = 1;
			while (size > TILE) {
				size /= 2;
				levels++;
			}
			OMETiffExporter.builder().put(sources).defineMetaData("Image")
				.defineWriteOptions().tileSize(TILE, TILE).nResolutionLevels(levels)
				.downsample(2).compression(codec)
				.maxTilesInQueue(64).nThreads(THREADS).savePath(output.getAbsolutePath())
				.create().export();
		}
		finally {
			info.readerPool.shutDown(reader -> {
				try {
					reader.close();
				}
				catch (IOException e) {
					e.printStackTrace();
				}
			});
		}
	}
}
