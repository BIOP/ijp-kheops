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

import java.io.File;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * A minimal benchmark harness: runs a task a few times, keeps the median, and
 * prints a table which can be pasted into an issue. Results are also written as
 * CSV in {@code target/benchmark-results} so that two runs can be compared.
 * <p>
 * This is deliberately not JMH: the tasks measured here take seconds and are
 * dominated by I/O and by work spread over many threads, which is not what JMH
 * is meant for. The only micro-benchmark, {@link ScalerBenchmark}, is long
 * enough per invocation to be measured this way, provided the warmup runs.
 */
public class Bench {

	public static final File RESULTS_DIR = new File("target",
		"benchmark-results");

	/** A task which may fail */
	public interface Task {

		void run() throws Exception;
	}

	/** The timings of one benchmarked configuration */
	public static class Result {

		public final String name;
		public final double[] timesMs;
		/** Amount of data processed by one run, in MB, or 0 if not relevant */
		public final double workMB;

		Result(String name, double[] timesMs, double workMB) {
			this.name = name;
			this.timesMs = timesMs;
			this.workMB = workMB;
		}

		public double medianMs() {
			double[] sorted = timesMs.clone();
			Arrays.sort(sorted);
			int n = sorted.length;
			return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
		}

		public double minMs() {
			return Arrays.stream(timesMs).min().orElse(Double.NaN);
		}

		public double maxMs() {
			return Arrays.stream(timesMs).max().orElse(Double.NaN);
		}

		/** @return the spread of the measurements, in percent of the median */
		public double spreadPercent() {
			return 100 * (maxMs() - minMs()) / medianMs();
		}

		/** @return the throughput in MB/s, or NaN if no amount of work was given */
		public double throughputMBs() {
			return workMB <= 0 ? Double.NaN : workMB / (medianMs() / 1000);
		}
	}

	/**
	 * Runs a task {@code warmup + repeats} times and keeps the timings of the
	 * last {@code repeats} ones.
	 *
	 * @param name label of the measured configuration
	 * @param warmup number of runs to discard
	 * @param repeats number of runs to keep
	 * @param workMB amount of data processed by a single run, in MB, used to
	 *          report a throughput - pass 0 if not relevant
	 * @param task what to measure
	 * @return the timings
	 */
	public static Result measure(String name, int warmup, int repeats,
		double workMB, Task task) throws Exception
	{
		System.out.print("  " + name + " ");
		for (int i = 0; i < warmup; i++) {
			task.run();
			System.out.print(".");
		}
		double[] times = new double[repeats];
		for (int i = 0; i < repeats; i++) {
			System.gc();
			long start = System.nanoTime();
			task.run();
			times[i] = (System.nanoTime() - start) / 1e6;
			System.out.print("*");
		}
		Result result = new Result(name, times, workMB);
		System.out.printf(" %.0f ms%n", result.medianMs());
		return result;
	}

	/**
	 * Prints the results as a table and writes them next to the build output.
	 * The last column compares every configuration with the first one, which
	 * only makes sense when they all do the same amount of work.
	 */
	public static void report(String title, List<Result> results) {
		report(title, results, true);
	}

	public static void report(String title, List<Result> results,
		boolean compareToFirst)
	{
		int width = 8;
		for (Result r : results)
			width = Math.max(width, r.name.length());
		boolean throughput = results.stream().anyMatch(r -> r.workMB > 0);

		StringBuilder table = new StringBuilder();
		table.append("\n").append(title).append("\n");
		String format = "%-" + width + "s  %10s  %10s  %8s" + (throughput
			? "  %10s  %8s" : "") + "%n";
		table.append(String.format(format, "configuration", "median ms", "min ms",
			"spread", "MB/s", "vs first"));
		for (int i = 0; i < results.size(); i++) {
			Result r = results.get(i);
			String speedup = !compareToFirst ? "" : i == 0 ? "-" : String.format(
				"x%.2f", results.get(0).medianMs() / r.medianMs());
			if (throughput) {
				table.append(String.format(format, r.name, String.format("%.0f", r
					.medianMs()), String.format("%.0f", r.minMs()), String.format("%.0f%%", r
						.spreadPercent()), String.format("%.1f", r.throughputMBs()), speedup));
			}
			else {
				table.append(String.format("%-" + width +
					"s  %10.0f  %10.0f  %7.0f%%  %8s%n", r.name, r.medianMs(), r.minMs(), r
						.spreadPercent(), speedup));
			}
		}
		System.out.println(table);
		writeCsv(title, results);
	}

	private static void writeCsv(String title, List<Result> results) {
		if (!RESULTS_DIR.exists() && !RESULTS_DIR.mkdirs()) {
			System.err.println("Could not create " + RESULTS_DIR);
			return;
		}
		String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
		String safeTitle = title.replaceAll("[^a-zA-Z0-9]+", "-").toLowerCase();
		File csv = new File(RESULTS_DIR, safeTitle + "-" + stamp + ".csv");
		try (PrintWriter out = new PrintWriter(csv, "UTF-8")) {
			out.println("configuration,median_ms,min_ms,max_ms,work_mb,throughput_mb_s,runs");
			for (Result r : results) {
				out.printf("\"%s\",%.1f,%.1f,%.1f,%.1f,%.2f,\"%s\"%n", r.name, r
					.medianMs(), r.minMs(), r.maxMs(), r.workMB, r.throughputMBs(), Arrays
						.toString(r.timesMs));
			}
			System.out.println("Written to " + csv.getAbsolutePath() + "\n");
		}
		catch (Exception e) {
			System.err.println("Could not write " + csv + ": " + e.getMessage());
		}
	}

	/** Prints the environment the measurements were made in */
	public static void printEnvironment() {
		Runtime runtime = Runtime.getRuntime();
		System.out.println("Java " + System.getProperty("java.version") + " on " +
			System.getProperty("os.name") + ", " + runtime.availableProcessors() +
			" processors, max heap " + (runtime.maxMemory() >> 20) + " MB");
	}

	/** Keeps the JIT from removing the computation of a result */
	public static long sink;

	/** Consumes a byte array so that the computation which built it is kept */
	public static void consume(byte[] bytes) {
		long sum = 0;
		for (int i = 0; i < bytes.length; i += 997) {
			sum += bytes[i];
		}
		sink += sum;
	}

	/** @return an empty, modifiable list of results */
	public static List<Result> results() {
		return new ArrayList<>();
	}
}
