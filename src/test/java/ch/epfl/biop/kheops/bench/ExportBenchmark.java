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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * End to end benchmark of the pyramidal OME-TIFF export.
 * <p>
 * The baseline configuration is the one the
 * {@link ch.epfl.biop.kheops.command.KheopsCommand} uses today: 1024 pixel
 * tiles, LZW compression, one worker thread per processor, a single
 * Bio-Formats reader for the source, and a progress monitor.
 * Every other configuration changes one factor only, so the difference is
 * attributable.
 * <p>
 * <b>What each variant tells:</b>
 * <ul>
 * <li><i>reader pool = threads</i>: the source is currently read through a pool
 * of a single Bio-Formats reader, whatever the number of worker threads, so all
 * decoding of the full resolution level is serialized.</li>
 * <li><i>no progress monitor</i>: the exporter reports progress once per tile,
 * and a SciJava task event captures a stack trace and is dispatched
 * synchronously on the writing thread. This is the upper bound of what
 * throttling the progress reports can give.</li>
 * <li><i>uncompressed</i>: tells whether the single writing thread is saturated
 * by the compression, that is, whether the export is writer bound or source
 * bound.</li>
 * <li><i>1 thread</i>: how much the multithreading is worth today.</li>
 * </ul>
 * <p>
 * Run with:
 *
 * <pre>
 * mvn -Denforcer.skip=true test-compile
 * java -Xmx12g -cp &lt;test classpath&gt; ch.epfl.biop.kheops.bench.ExportBenchmark [dataset...]
 * </pre>
 *
 * where a dataset is one of {@code vsi}, {@code czi}, {@code lif},
 * {@code synthetic}, {@code giant}, or {@code memory}. All of them except
 * {@code giant} are run if none is given: the giant plane is opt in because it
 * generates a 1.2 GB source and takes far longer than the others. The real
 * datasets have to be cached first, see {@link BenchmarkDatasets}; the
 * synthetic ones are generated on first use.
 */
public class ExportBenchmark {

	private static final int TILE = 1024;
	private static final int WARMUP = 1;
	private static final int REPEATS = 3;
	private static final int MAX_TILES_IN_QUEUE = 64;
	private static final int PROCESSORS = Runtime.getRuntime()
		.availableProcessors();
	private static final int DEFAULT_THREADS = Math.max(1, PROCESSORS - 1);
	/**
	 * A configuration which does not finish within this time is reported as hung
	 * and skipped: a reader pool of a single reader deadlocks the opening of
	 * multi series CZI files, and there is no point in waiting for it.
	 */
	private static final int TIMEOUT_SECONDS = Integer.getInteger(
		"kheops.bench.timeout", 900);

	/**
	 * Where the exported files are written and deleted again. Override with
	 * {@code -Dkheops.bench.outputDir=...}: an export writes both its output and
	 * its temporary pyramid file here, so this is where most of the disk traffic
	 * goes, and the system temporary directory is rarely the right drive for it.
	 */
	private static final File OUTPUT_DIR = new File(System.getProperty(
		"kheops.bench.outputDir", new File(System.getProperty("java.io.tmpdir"),
			"kheops-bench-output").getAbsolutePath()));

	// ------------------------------------------------------------ configuration

	/** One set of export options, differing from the baseline by one factor */
	static class Config {

		final String label;
		int nThreads = DEFAULT_THREADS;
		int readerPoolSize = 1;
		boolean monitor = true;
		String compression = "LZW";
		/** Writes the full resolution level only: no temporary file, no scaling */
		boolean singleResolution = false;
		/** Whether the workers compress tiles, instead of the writing thread */
		boolean precompress = true;
		int tile = TILE;
		int queue = MAX_TILES_IN_QUEUE;
		/** Whether the series of a multi series file are exported concurrently */
		boolean parallelSeries = false;

		Config(String label) {
			this.label = label;
		}

		Config writerCompresses() {
			precompress = false;
			return this;
		}

		Config noPyramid() {
			singleResolution = true;
			return this;
		}

		Config threads(int n) {
			nThreads = n;
			return this;
		}

		Config readerPool(int n) {
			readerPoolSize = n;
			return this;
		}

		Config noMonitor() {
			monitor = false;
			return this;
		}

		Config uncompressed() {
			compression = "Uncompressed";
			return this;
		}

		Config tile(int size) {
			tile = size;
			return this;
		}

		Config queue(int n) {
			queue = n;
			return this;
		}

		Config parallelSeries() {
			parallelSeries = true;
			return this;
		}
	}

	private static List<Config> configurations() {
		List<Config> configs = new ArrayList<>();
		configs.add(new Config("baseline (as KheopsCommand)"));
		configs.add(new Config("reader pool = threads").readerPool(
			DEFAULT_THREADS));
		configs.add(new Config("no progress monitor").noMonitor());
		configs.add(new Config("uncompressed").uncompressed());
		// The exporter hands the writer tiles the workers already compressed. This
		// row is what the writing thread costs when it compresses them itself
		configs.add(new Config("writer compresses").writerCompresses());
		configs.add(new Config("1 worker thread").threads(1));
		configs.add(new Config("reader pool + no monitor").readerPool(
			DEFAULT_THREADS).noMonitor());
		// Does less work than the others on purpose: the difference with the
		// baseline is what building the pyramid costs today, temporary file
		// included
		configs.add(new Config("no pyramid (level 0 only)").noPyramid());
		// Isolates where the source reader pool can matter at all: only resolution
		// level 0 reads through the Sources, the levels above read the temporary
		// file with an OMETiffReader that each thread creates for itself
		configs.add(new Config("no pyramid + reader pool").noPyramid().readerPool(
			DEFAULT_THREADS));
		return configs;
	}

	// -------------------------------------------------------------------- runs

	public static void main(String... args) throws Exception {
		DebugTools.setRootLevel("OFF");
		Bench.printEnvironment();
		prepareOutputDir();

		List<String> datasets = new ArrayList<>();
		for (String arg : args)
			datasets.add(arg.toLowerCase());
		if (datasets.isEmpty()) {
			datasets.add("memory");
			datasets.add("synthetic");
			datasets.add("vsi");
			datasets.add("vsirgb");
			datasets.add("czi");
			datasets.add("lif");
		}

		// Only the task service is needed: the Bio-Formats opener ignores the
		// context. A full context would start the ImageJ legacy service, which
		// rewrites the already loaded ImageJ 1.x classes and fails.
		Context context = new Context(TaskService.class);
		try {
			if (datasets.contains("list")) {
				listSeries("VSI brain slide", BenchmarkDatasets.brainSlideVSI(), context);
				listSeries("CZI mouse brain", BenchmarkDatasets.mouseBrainCZI(),
					context);
				listSeries("LIF MelSTR", BenchmarkDatasets.melSTRLIF(), context);
				return;
			}
			for (String dataset : datasets) {
				switch (dataset) {
					case "memory":
						benchmarkInMemory(context);
						break;
					case "synthetic":
						benchmarkFile("synthetic uint16, " + SyntheticImages.SIZE_X + "x" +
							SyntheticImages.SIZE_Y + ", " + SyntheticImages.SIZE_C + " channels",
							SyntheticImages.uint16File(), 0, context);
						break;
					case "giant":
						benchmarkFile("synthetic uint16, one giant plane " +
							SyntheticImages.GIANT_SIZE + "x" + SyntheticImages.GIANT_SIZE +
							", 1 channel", SyntheticImages.giantUint16File(), 0, context);
						break;
					case "multiseries":
						benchmarkMultiSeries(context);
						break;
					case "standard":
						benchmarkStandard(context);
						break;
					case "manyplanes":
						benchmarkFile("synthetic uint16, many small planes", SyntheticImages
							.manyPlanesUint16File(), 0, context);
						break;
					case "vsi":
						benchmarkFile("VSI brain slide, series 10x_01 (uint16, 3 channels)",
							BenchmarkDatasets.brainSlideVSI(), vsiSeries(), context);
						break;
					case "vsirgb":
						benchmarkFile("VSI brain slide, overview (RGB)", BenchmarkDatasets
							.brainSlideVSI(), VSI_RGB_SERIES, context);
						break;
					case "poolsweep":
						poolSweep("VSI brain slide, series 10x_01", BenchmarkDatasets
							.brainSlideVSI(), vsiSeries(), context);
						break;
					case "czi":
						benchmarkFile("CZI mouse brain, series 0", BenchmarkDatasets
							.mouseBrainCZI(), 0, context);
						break;
					case "lif":
						benchmarkFile("LIF MelSTR, series 0", BenchmarkDatasets.melSTRLIF(),
							0, context);
						break;
					default:
						System.err.println("Unknown dataset: " + dataset);
				}
			}
		}
		finally {
			context.dispose();
		}
	}

	/**
	 * Series 2 of the VSI slide is 10x_01, the first fluorescent tile: 7230x6151
	 * pixels, 3 channels of 16 bits. Series 0 and 1 are the label and the
	 * overview, which are RGB.
	 */
	private static int vsiSeries() {
		return Integer.parseInt(System.getProperty("kheops.bench.vsiSeries", "2"));
	}

	/** The RGB overview of the VSI slide, 16172x6817 pixels */
	private static final int VSI_RGB_SERIES = 1;

	/**
	 * Export of an image which is already in memory: no decoding at all, so this
	 * measures the writing side only - the conversion to bytes, the downsampling
	 * and the TIFF writer.
	 */
	private static void benchmarkInMemory(Context context) throws Exception {
		int sizeX = SyntheticImages.SIZE_X, sizeY = SyntheticImages.SIZE_Y;
		int sizeC = SyntheticImages.SIZE_C;
		List<RandomAccessibleInterval<?>> channels = new ArrayList<>();
		for (int c = 0; c < sizeC; c++)
			channels.add(SyntheticImages.uint16Image(sizeX, sizeY, c));
		double rawMB = (double) sizeX * sizeY * sizeC * 2 / (1024 * 1024);
		int nResolutions = resolutionLevels(Math.min(sizeX, sizeY));

		System.out.println("\n### In memory uint16, " + sizeX + "x" + sizeY + ", " +
			sizeC + " channels (" + String.format("%.0f", rawMB) + " MB raw, " +
			nResolutions + " resolution levels)");
		List<Bench.Result> results = Bench.results();
		for (Config config : configurations()) {
			// The source is in memory: the size of the reader pool is irrelevant
			if (config.readerPoolSize != 1) continue;
			int levels = config.singleResolution ? 1 : nResolutions;
			results.add(Bench.measure(config.label, WARMUP, REPEATS, rawMB, () -> {
				File output = newOutputFile();
				try {
					exportInMemory(channels, levels, config, output, context);
				}
				finally {
					deleteOutputs(output);
				}
			}));
		}
		Bench.report("In memory uint16 " + sizeX + "x" + sizeY + "x" + sizeC,
			results);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static void exportInMemory(List<RandomAccessibleInterval<?>> channels,
		int nResolutions, Config config, File output, Context context)
		throws Exception
	{
		applyPrecompression(config);
		try {
			OMETiffExporter.OMETiffExporterBuilder.Data.DataBuilder data =
				OMETiffExporter.builder();
			for (int c = 0; c < channels.size(); c++) {
				data.putXYZRAI(c, 0, (RandomAccessibleInterval) channels.get(c));
			}
			OMETiffExporter.OMETiffExporterBuilder.WriterOptions.WriterOptionsBuilder writer =
				data.defineMetaData("Image").defineWriteOptions().tileSize(TILE, TILE)
					.nResolutionLevels(nResolutions).downsample(2).compression(
						config.compression)
					.maxTilesInQueue(MAX_TILES_IN_QUEUE).nThreads(config.nThreads).savePath(
						output.getAbsolutePath());
			if (config.monitor) writer.monitor(context.getService(TaskService.class));
			writer.create().export();
		}
		finally {
			clearPrecompression();
		}
	}

	/**
	 * Pre-compression is switched off through a system property rather than a
	 * writer option: it is an escape hatch, not a feature, see
	 * {@code OMETiffExporter.precompressionCodec}.
	 */
	private static void applyPrecompression(Config config) {
		System.setProperty("kheops.precompress", Boolean.toString(
			config.precompress));
	}

	private static void clearPrecompression() {
		System.clearProperty("kheops.precompress");
	}

	/**
	 * Export of one series of a file, through the same source loading path as the
	 * Kheops command.
	 */
	private static void benchmarkFile(String title, File input, int series,
		Context context) throws Exception
	{
		if (!input.exists()) {
			System.err.println("Missing dataset " + input + " - run " +
				BenchmarkDatasets.class.getName() + " first");
			return;
		}
		System.out.println("\n### " + title + "\n    " + input);
		Description description = describe(input, series, context);
		System.out.println("    " + description);

		List<Bench.Result> results = Bench.results();
		for (Config config : configurations()) {
			Bench.Result result = measureOrSkip(config.label, description.rawMB, () -> {
				File output = newOutputFile();
				try {
					exportFromFile(input, series, config, output, context);
				}
				finally {
					deleteOutputs(output);
				}
			});
			if (result != null) results.add(result);
		}
		Bench.report(title, results);
	}

	/**
	 * @return the timings, or null if the configuration hung or failed - the
	 *         other configurations are still worth measuring
	 */
	private static Bench.Result measureOrSkip(String label, double workMB,
		Bench.Task task)
	{
		try {
			return Bench.measure(label, WARMUP, REPEATS, workMB, () -> runWithTimeout(
				task));
		}
		catch (Exception e) {
			System.out.println("  " + label + " -> skipped: " + e);
			return null;
		}
	}

	/** Runs a task on a daemon thread, so that a deadlock does not block us */
	private static void runWithTimeout(Bench.Task task) throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "bench");
			thread.setDaemon(true);
			return thread;
		});
		try {
			Future<?> future = executor.submit(() -> {
				task.run();
				return null;
			});
			future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		catch (ExecutionException e) {
			throw new IllegalStateException(e.getCause());
		}
		catch (TimeoutException e) {
			throw new IllegalStateException("no result after " + TIMEOUT_SECONDS +
				" s, the export is very likely deadlocked");
		}
		finally {
			executor.shutdownNow();
		}
	}

	/**
	 * Varies the size of the source reader pool and nothing else, to pick a
	 * sensible value rather than guessing one: the pool costs one Bio-Formats
	 * reader in memory per slot.
	 */
	private static void poolSweep(String title, File input, int series,
		Context context) throws Exception
	{
		if (!input.exists()) {
			System.err.println("Missing dataset " + input);
			return;
		}
		System.out.println("\n### Reader pool sweep: " + title);
		Description description = describe(input, series, context);
		System.out.println("    " + description);
		List<Bench.Result> results = Bench.results();
		for (int poolSize : new int[] { 1, 2, 4, 8, 16, DEFAULT_THREADS }) {
			final Config config = new Config("reader pool " + poolSize).readerPool(
				poolSize);
			Bench.Result result = measureOrSkip(config.label, description.rawMB,
				() -> {
					File output = newOutputFile();
					try {
						exportFromFile(input, series, config, output, context);
					}
					finally {
						deleteOutputs(output);
					}
				});
			if (result != null) results.add(result);
		}
		Bench.report("Reader pool sweep, " + title, results);
	}

	/** Size of a series, known before any export is measured */
	private static class Description {

		int sizeX, sizeY, sizeZ, sizeC, sizeT, nResolutions;
		double rawMB;

		@Override
		public String toString() {
			return String.format("%dx%d, %d z, %d channel(s), %d timepoint(s), " +
				"%.0f MB raw, %d resolution levels", sizeX, sizeY, sizeZ, sizeC, sizeT,
				rawMB, nResolutions);
		}
	}

	private static Description describe(File input, int series, Context context)
		throws Exception
	{
		// Not a pool of 1: that deadlocks on multi series CZI files, and this
		// method only collects sizes, it is not measured
		KheopsHelper.SourcesInfo info = openSources(input, 4, context);
		try {
			SourceAndConverter[] sources = sourcesOfSeries(info, input, series);
			RandomAccessibleInterval<?> model = sources[0].getSpimSource().getSource(0,
				0);
			Description description = new Description();
			description.sizeX = (int) model.dimension(0);
			description.sizeY = (int) model.dimension(1);
			description.sizeZ = (int) model.dimension(2);
			description.sizeC = sources.length;
			description.sizeT = 0;
			while (sources[0].getSpimSource().isPresent(description.sizeT))
				description.sizeT++;
			description.nResolutions = resolutionLevels(Math.min(description.sizeX,
				description.sizeY));
			description.rawMB = (double) description.sizeX * description.sizeY *
				description.sizeZ * description.sizeC * description.sizeT *
				bytesPerPixel(sources[0]) / (1024 * 1024);
			return description;
		}
		finally {
			shutDown(info);
		}
	}

	private static void exportFromFile(File input, int series, Config config,
		File output, Context context) throws Exception
	{
		KheopsHelper.SourcesInfo info = openSources(input, config.readerPoolSize,
			config.tile, config.queue, context);
		applyPrecompression(config);
		try {
			exportSources(sourcesOfSeries(info, input, series), config, output,
				context);
		}
		finally {
			clearPrecompression();
			shutDown(info);
		}
	}

	/**
	 * Exports one series through the exporter, with the tile size, queue depth
	 * and thread count of the configuration. Neither opens nor closes the
	 * sources: the multi series benchmark exports several series out of the same
	 * reader pool.
	 */
	@SuppressWarnings("rawtypes")
	private static void exportSources(SourceAndConverter[] sources, Config config,
		File output, Context context) throws Exception
	{
		RandomAccessibleInterval<?> model = sources[0].getSpimSource().getSource(0,
			0);
		int nResolutions = config.singleResolution ? 1 : resolutionLevels((int) Math
			.min(model.dimension(0), model.dimension(1)), config.tile);

		OMETiffExporter.OMETiffExporterBuilder.WriterOptions.WriterOptionsBuilder writer =
			OMETiffExporter.builder().put(sources).defineMetaData("Image")
				.defineWriteOptions().tileSize(config.tile, config.tile)
				.nResolutionLevels(nResolutions).downsample(2).compression(
					config.compression).maxTilesInQueue(config.queue).nThreads(
						config.nThreads).savePath(output.getAbsolutePath());
		if (config.monitor) writer.monitor(context.getService(TaskService.class));
		writer.create().export();
	}

	private static KheopsHelper.SourcesInfo openSources(File input,
		int readerPoolSize, Context context)
	{
		return openSources(input, readerPoolSize, TILE, MAX_TILES_IN_QUEUE, context);
	}

	private static KheopsHelper.SourcesInfo openSources(File input,
		int readerPoolSize, int tile, int queue, Context context)
	{
		return KheopsHelper.getSourcesFromFile(input.getAbsolutePath(), tile, tile,
			queue, readerPoolSize, false, "CORNER", context);
	}

	@SuppressWarnings("rawtypes")
	private static SourceAndConverter[] sourcesOfSeries(
		KheopsHelper.SourcesInfo info, File input, int series)
	{
		List<SourceAndConverter> sources = info.idToSources.get(series);
		if (sources == null) {
			throw new IllegalArgumentException("No series " + series + " in " + input +
				" - available: " + info.idToSources.keySet());
		}
		return sources.toArray(new SourceAndConverter[0]);
	}

	private static void shutDown(KheopsHelper.SourcesInfo info) {
		info.readerPool.shutDown(reader -> {
			try {
				reader.close();
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		});
	}

	/** Prints the series a file holds, with the indices this benchmark uses */
	private static void listSeries(String title, File input, Context context) {
		if (!input.exists()) {
			System.err.println("Missing dataset " + input);
			return;
		}
		System.out.println("\n### " + title + "\n    " + input);
		KheopsHelper.SourcesInfo info = openSources(input, 4, context);
		try {
			List<Integer> ids = new ArrayList<>(info.idToSources.keySet());
			ids.sort(null);
			for (int id : ids) {
				SourceAndConverter<?> source = info.idToSources.get(id).get(0);
				RandomAccessibleInterval<?> model = source.getSpimSource().getSource(0,
					0);
				System.out.printf("   series %-4d %6dx%-6d z=%-4d channels=%-3d %-16s %s%n",
					id, model.dimension(0), model.dimension(1), model.dimension(2),
					info.idToSources.get(id).size(), source.getSpimSource().getType()
						.getClass().getSimpleName(), source.getSpimSource().getName());
			}
		}
		finally {
			shutDown(info);
		}
	}

	private static int bytesPerPixel(SourceAndConverter<?> source) {
		Object pixel = source.getSpimSource().getType();
		if (pixel instanceof net.imglib2.type.numeric.integer.UnsignedByteType)
			return 1;
		if (pixel instanceof net.imglib2.type.numeric.ARGBType) return 3;
		if (pixel instanceof net.imglib2.type.numeric.real.FloatType) return 4;
		return 2;
	}

	/** Same rule as the Kheops command: downsample until a level fits in a tile */
	private static int resolutionLevels(int smallestSide) {
		return resolutionLevels(smallestSide, TILE);
	}

	private static int resolutionLevels(int smallestSide, int tile) {
		int size = smallestSide;
		int levels = 1;
		while (size > tile) {
			size /= 2;
			levels++;
		}
		return levels;
	}


	// ------------------------------------------------------------- standard

	/**
	 * What a user actually gets. {@code KheopsCommand} converts with LZW
	 * (`KheopsCommand.java:70`), 1024 pixel tiles, one worker thread per
	 * processor minus one, a reader pool of {@code Math.min(nThreads, 8)}
	 * (`KheopsCommand.java:133`), a queue of 64 tiles and a progress monitor.
	 * A file holding several series is the exception: each series is then
	 * exported by a single worker, with a queue of 4, and the series run
	 * concurrently.
	 * <p>
	 * This is the only benchmark here that changes nothing at all. Every other
	 * one varies a factor to attribute a cost; this one just reports the
	 * throughput of the defaults, per dataset.
	 */
	private static Config standardConfig(String label) {
		return new Config(label).readerPool(Math.min(DEFAULT_THREADS, 8));
	}

	private static void benchmarkStandard(Context context) throws Exception {
		System.out.println("\n### Standard mode: the defaults, LZW, nothing changed");
		List<Bench.Result> results = Bench.results();
		String synthetic = "synthetic, " + SyntheticImages.SIZE_X + "x" +
			SyntheticImages.SIZE_Y + ", " + SyntheticImages.SIZE_C + " ch";
		add(results, standardFile(synthetic, SyntheticImages.uint16File(), context));
		add(results, standardFile("giant, one " + SyntheticImages.GIANT_SIZE + "x" +
			SyntheticImages.GIANT_SIZE + " plane", SyntheticImages.giantUint16File(),
			context));
		// The same giant plane with the pyramid switched off. It does strictly
		// less work than the row above - it is a ceiling, not a speedup - and it
		// is the fastest number this benchmark can produce
		String level0 = "giant, level 0 only (no pyramid)";
		add(results, standardFile(level0, SyntheticImages.giantUint16File(),
			standardConfig(level0).noPyramid(), context));
		add(results, standardFile("many planes, " + SyntheticImages.MANY_PLANES_SIZE +
			"x" + SyntheticImages.MANY_PLANES_SIZE + ", " + SyntheticImages.MANY_PLANES_T +
			" t, " + SyntheticImages.MANY_PLANES_C + " ch", SyntheticImages
				.manyPlanesUint16File(), context));
		add(results, standardMultiSeries(context));
		// the rows are different datasets, not variants of one configuration:
		// comparing them to the first would be meaningless
		Bench.report("Standard mode, defaults, LZW", results, false);
	}

	private static void add(List<Bench.Result> results, Bench.Result result) {
		if (result != null) results.add(result);
	}

	private static Bench.Result standardFile(String label, File input,
		Context context) throws Exception
	{
		return standardFile(label, input, standardConfig(label), context);
	}

	private static Bench.Result standardFile(String label, File input,
		Config config, Context context) throws Exception
	{
		Description description = describe(input, 0, context);
		System.out.println("    " + label + "\n      " + description);
		return measureOrSkip(label, description.rawMB, () -> {
			File output = newOutputFile();
			try {
				exportFromFile(input, 0, config, output, context);
			}
			finally {
				deleteOutputs(output);
			}
		});
	}

	/** The multi series default: one worker per series, series in parallel */
	private static Bench.Result standardMultiSeries(Context context)
		throws Exception
	{
		File input = SyntheticImages.multiSeriesUint16File();
		String label = SyntheticImages.MULTI_SERIES_COUNT + " series, " +
			SyntheticImages.MULTI_SERIES_SIZE + "x" + SyntheticImages.MULTI_SERIES_SIZE;
		Config config = new Config(label).parallelSeries().threads(1).queue(4)
			.readerPool(Math.min(DEFAULT_THREADS, 8));
		double rawMB = multiSeriesRawMB(input, context);
		System.out.printf("    %s%n      %.0f MB raw in total%n", label, rawMB);
		return measureOrSkip(label, rawMB, () -> exportAllSeries(input, config,
			context));
	}

	/** @return the raw size of every series of a multi series file, in MB */
	@SuppressWarnings("rawtypes")
	private static double multiSeriesRawMB(File input, Context context) {
		KheopsHelper.SourcesInfo info = openSources(input, 8, context);
		try {
			SourceAndConverter[] sources = sourcesOfSeries(info, input, 0);
			RandomAccessibleInterval<?> model = sources[0].getSpimSource().getSource(0,
				0);
			return (double) info.idToSources.size() * model.dimension(0) * model
				.dimension(1) * bytesPerPixel(sources[0]) / (1024 * 1024);
		}
		finally {
			shutDown(info);
		}
	}

	// ---------------------------------------------------------- multi series

	/**
	 * The configuration a multi series file is really exported with today.
	 * {@code KheopsCommand} sets {@code process_series_in_parallel} as soon as a
	 * file holds more than one series, and then gives every series a single
	 * worker thread and a queue of 4 tiles instead of 64
	 * ({@code KheopsCommand.java:291-293}). Series run concurrently, so the
	 * machine is busy even though each export is single threaded.
	 * <p>
	 * None of the other datasets measure this: they export one series with all
	 * the worker threads, which is what {@code KheopsCommand} does only for a
	 * single series file. Since the archetypal Kheops input - a slide scanner
	 * file, a LIF, a CZI - holds several series, this is the path most exports
	 * actually take.
	 */
	private static List<Config> multiSeriesConfigurations() {
		List<Config> configs = new ArrayList<>();
		// what KheopsCommand does today for any file with more than one series
		configs.add(new Config("parallel series, 1 worker (as KheopsCommand)")
			.parallelSeries().threads(1).queue(4).readerPool(8));
		// the queue depth is the only thing KheopsCommand changes besides the
		// thread count, so it is worth separating the two
		configs.add(new Config("parallel series, 1 worker, queue 64")
			.parallelSeries().threads(1).queue(64).readerPool(8));
		configs.add(new Config("parallel series, 2 workers").parallelSeries()
			.threads(2).queue(4).readerPool(8));
		// the other strategy: one series at a time, all the threads on it. This is
		// what the single series path does, applied to every series in turn
		configs.add(new Config("serial series, all workers").queue(64).readerPool(
			8));
		// the reader pool is shared by every concurrent export, so it is a
		// plausible bottleneck of the parallel strategy
		configs.add(new Config("parallel series, 1 worker, pool = threads")
			.parallelSeries().threads(1).queue(4).readerPool(DEFAULT_THREADS));
		configs.add(new Config("parallel series, 1 worker, uncompressed")
			.parallelSeries().threads(1).queue(4).readerPool(8).uncompressed());
		// The three single factor changes above are each worth something on their
		// own. These combine them: more workers per series and a larger reader
		// pool both want the same idle cores, so they may compound or cancel
		configs.add(new Config("parallel series, 2 workers, pool = threads")
			.parallelSeries().threads(2).queue(4).readerPool(DEFAULT_THREADS));
		configs.add(new Config("parallel series, 2 workers, pool = threads, queue 64")
			.parallelSeries().threads(2).queue(64).readerPool(DEFAULT_THREADS));
		// the queue depth was noise when the workers were starved by the reader
		// pool; it may stop being noise once they are not
		configs.add(new Config("parallel series, 4 workers, pool = threads, queue 64")
			.parallelSeries().threads(4).queue(64).readerPool(DEFAULT_THREADS));
		return configs;
	}

	/**
	 * Exports every series of a generated multi series file, either concurrently
	 * with one worker each or one after the other with every worker, and reports
	 * the total time for the whole file.
	 */
	private static void benchmarkMultiSeries(Context context) throws Exception {
		File input = SyntheticImages.multiSeriesUint16File();
		System.out.println("\n### synthetic uint16, " +
			SyntheticImages.MULTI_SERIES_COUNT + " series of " +
			SyntheticImages.MULTI_SERIES_SIZE + "x" + SyntheticImages.MULTI_SERIES_SIZE +
			", 1 channel\n    " + input);

		KheopsHelper.SourcesInfo info = openSources(input, 8, context);
		int nSeries;
		double rawMB;
		try {
			nSeries = info.idToSources.size();
			Description first = new Description();
			SourceAndConverter[] sources = sourcesOfSeries(info, input, 0);
			RandomAccessibleInterval<?> model = sources[0].getSpimSource().getSource(0,
				0);
			first.sizeX = (int) model.dimension(0);
			first.sizeY = (int) model.dimension(1);
			rawMB = (double) nSeries * first.sizeX * first.sizeY * bytesPerPixel(
				sources[0]) / (1024 * 1024);
		}
		finally {
			shutDown(info);
		}
		System.out.printf("    %d series, %.0f MB raw in total, %d resolution " +
			"levels each%n", nSeries, rawMB, resolutionLevels(
				SyntheticImages.MULTI_SERIES_SIZE));

		List<Bench.Result> results = Bench.results();
		for (Config config : multiSeriesConfigurations()) {
			Bench.Result result = measureOrSkip(config.label, rawMB, () -> exportAllSeries(
				input, config, context));
			if (result != null) results.add(result);
		}
		Bench.report("Multi series, " + nSeries + " x " +
			SyntheticImages.MULTI_SERIES_SIZE + "x" +
			SyntheticImages.MULTI_SERIES_SIZE, results);
	}

	/**
	 * Exports every series of a file out of one shared reader pool, the way the
	 * Kheops command does. The outputs are deleted as they are produced rather
	 * than at the end, so that the disk holds one output per concurrent export
	 * and not the whole file.
	 */
	private static void exportAllSeries(File input, Config config, Context context)
		throws Exception
	{
		KheopsHelper.SourcesInfo info = openSources(input, config.readerPoolSize,
			config.tile, config.queue, context);
		applyPrecompression(config);
		try {
			List<Integer> ids = new ArrayList<>(info.idToSources.keySet());
			ids.sort(null);
			if (config.parallelSeries) {
				// a pool of its own rather than the common one, so that the number of
				// concurrent exports is the same from one run to the next
				ForkJoinPool pool = new ForkJoinPool(DEFAULT_THREADS);
				try {
					pool.submit(() -> ids.parallelStream().forEach(id -> exportOneSeries(
						info, input, id, config, context))).get();
				}
				finally {
					pool.shutdown();
				}
			}
			else {
				for (int id : ids)
					exportOneSeries(info, input, id, config, context);
			}
		}
		finally {
			clearPrecompression();
			shutDown(info);
		}
	}

	/** Wraps the checked exceptions so that this can run inside a stream */
	private static void exportOneSeries(KheopsHelper.SourcesInfo info, File input,
		int id, Config config, Context context)
	{
		File output = newOutputFile();
		try {
			exportSources(sourcesOfSeries(info, input, id), config, output, context);
		}
		catch (Exception e) {
			throw new IllegalStateException("Series " + id + " failed", e);
		}
		finally {
			deleteOutputs(output);
		}
	}
	// ------------------------------------------------------------------ output

	/** Atomic: the multi series benchmark exports several series at once */
	private static final java.util.concurrent.atomic.AtomicInteger counter =
		new java.util.concurrent.atomic.AtomicInteger();

	private static File newOutputFile() {
		return new File(OUTPUT_DIR, "bench_" + counter.getAndIncrement() +
			".ome.tiff");
	}

	private static void prepareOutputDir() {
		if (!OUTPUT_DIR.exists() && !OUTPUT_DIR.mkdirs()) {
			throw new IllegalStateException("Could not create " + OUTPUT_DIR);
		}
		File[] leftovers = OUTPUT_DIR.listFiles();
		if (leftovers != null) for (File file : leftovers)
			delete(file);
		System.out.println("Exporting to " + OUTPUT_DIR);
	}

	/**
	 * Deletes an export and the temporary pyramid files that went with it.
	 * {@code OMETiffExporter} writes one {@code <name>_lvl_<r>.ome.tiff} per
	 * resolution level below the last; a failed export leaves them behind, and
	 * on the giant dataset that is several gigabytes per attempt.
	 */
	private static void deleteOutputs(File output) {
		delete(output);
		String base = output.getName();
		int extension = base.indexOf(".ome");
		String prefix = (extension < 0 ? base : base.substring(0, extension)) +
			".ome_lvl_";
		File[] leftovers = OUTPUT_DIR.listFiles((dir, name) -> name.startsWith(
			prefix));
		if (leftovers != null) for (File file : leftovers)
			delete(file);
	}

	private static void delete(File file) {
		if (file.exists() && !file.delete()) {
			System.err.println("Could not delete " + file);
		}
	}
}
