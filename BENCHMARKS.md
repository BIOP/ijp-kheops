# Benchmarks

Measurements of the pyramidal OME-TIFF export, and the reasoning they support.
The code lives in `src/test/java/ch/epfl/biop/kheops/bench/`. These are plain
`main()` classes, **not** JUnit tests: `mvn test` does not run them.

## Why they exist

Reading the exporter suggests several plausible bottlenecks. Most of them turned
out to be wrong. The numbers below exist so that the next optimisation attempt
starts from evidence rather than from intuition.

## Running them

The datasets are downloaded once into `~/CachedSamples` (via `DatasetHelper`
from `bigdataviewer-image-loaders`) and are never stored in this repository.

```
mvn -Denforcer.skip=true test-compile
java -cp <test classpath> ch.epfl.biop.kheops.bench.BenchmarkDatasets   # ~1.8 GB, once
java -Xmx12g -cp <test classpath> ch.epfl.biop.kheops.bench.ExportBenchmark
java -Xmx12g -cp <test classpath> ch.epfl.biop.kheops.bench.ExportBenchmark list
java -cp <test classpath> ch.epfl.biop.kheops.bench.ScalerBenchmark
java -Xmx12g -cp <test classpath> ch.epfl.biop.kheops.bench.CompressionBenchmark vsi
```

`ExportBenchmark` accepts any of `memory`, `synthetic`, `vsi`, `vsirgb`, `czi`,
`lif` (all of them if none is given), and `list` to print the series of each
file. Results are printed as a table and written as CSV to
`target/benchmark-results/`.

| class | what it measures |
| --- | --- |
| `ExportBenchmark` | a full export, one factor changed at a time |
| `ScalerBenchmark` | `AverageImageScaler.downsample` alone |
| `CompressionBenchmark` | wall time **and** output size per codec |
| `BenchmarkDatasets` | downloads and caches the real files |
| `SyntheticImages` | generates images whose decoding is nearly free |
| `Bench` | warmup, repeats, median, spread, CSV |

## Datasets

| name | content | stresses |
| --- | --- | --- |
| `memory` | 8000x6000, 3 ch, uint16, built in RAM | the writing side only, no decoding at all |
| `synthetic` | the same, as an uncompressed tiled OME-TIFF | the full pipeline with a nearly free decoder |
| `vsi` | Olympus VSI slide, series 2 `10x_01`, 7230x6151, 3 ch uint16 | the archetypal Kheops input, expensive JPEG decoding |
| `vsirgb` | the RGB overview of the same slide, 16172x6817 | the ARGB / interleaved path |
| `czi` | Zeiss CZI, 4 series, 1500x1000, 41 z ([Zenodo 19047136](https://zenodo.org/records/19047136)) | an expensive decoder, many planes, no pyramid |
| `lif` | Leica LIF, 9 series, 1024x1024, 3 ch, ~9 z ([Zenodo 13773035](https://zenodo.org/records/13773035)) | C/Z loops; too small to measure much else |

The VSI slide is slide 0 of [Zenodo 6553641](https://zenodo.org/records/6553641).
Comparing `synthetic` with `vsi` is what separates a decoding bottleneck from a
writing one: they export the same amount of data, through the same code, and
differ only in how expensive the source is to read.

## Results

Java 21.0.11, Windows 11, 32 logical processors, 12 GB heap, NVMe.
1 warmup + 3 repeats, median reported. Baseline = what `KheopsCommand` does
today: 1024 px tiles, LZW, uncompressed temporary files, 31 worker threads,
**a source reader pool of 1**, progress monitor on.

### In memory, 8000x6000 x 3 ch uint16 (275 MB raw, 4 levels)

| configuration | median | vs baseline |
| --- | ---: | ---: |
| baseline | 6787 ms | - |
| no progress monitor | 6955 ms | x0.98 |
| uncompressed | 3553 ms | x1.91 |
| 1 worker thread | 7233 ms | x0.94 |
| no pyramid (level 0 only) | 3459 ms | x1.96 |

### Synthetic file, same size (nearly free decoding)

| configuration | median | vs baseline |
| --- | ---: | ---: |
| baseline | 7029 ms | - |
| reader pool = threads | 7188 ms | x0.98 |
| uncompressed | 4238 ms | x1.66 |
| 1 worker thread | 7953 ms | x0.88 |
| no pyramid (level 0 only) | 4135 ms | x1.70 |

### VSI `10x_01`, 7230x6151 x 3 ch uint16 (real decoding)

| configuration | median | vs baseline |
| --- | ---: | ---: |
| baseline | 12057 ms | - |
| **reader pool = threads** | **6567 ms** | **x1.84** |
| no progress monitor | 11638 ms | x1.04 |
| uncompressed | 10460 ms | x1.15 |
| 1 worker thread | 12172 ms | x0.99 |
| reader pool + no monitor | 6583 ms | x1.83 |
| no pyramid (level 0 only) | 9242 ms | x1.30 |
| **no pyramid + reader pool** | **3774 ms** | **x3.20** |

### VSI RGB overview, 16172x6817

| configuration | median | vs baseline |
| --- | ---: | ---: |
| baseline | 19324 ms | - |
| **reader pool = threads** | **7472 ms** | **x2.59** |
| no progress monitor | 19562 ms | x0.99 |
| uncompressed | 18583 ms | x1.04 |
| 1 worker thread | 21919 ms | x0.88 |
| reader pool + no monitor | 7552 ms | x2.56 |
| no pyramid (level 0 only) | 17447 ms | x1.11 |
| **no pyramid + reader pool** | **4511 ms** | **x3.87** |

### Reader pool size sweep, VSI `10x_01`

| pool size | median | vs pool 1 |
| --- | ---: | ---: |
| 1 | 11918 ms | - |
| 2 | 8145 ms | x1.46 |
| 4 | 7045 ms | x1.69 |
| 8 | 6962 ms | x1.71 |
| 16 | 7127 ms | x1.67 |
| 31 | 6864 ms | x1.74 |

The benefit **saturates at 4 readers**: beyond that the single writing thread is
the limit, so more decoding parallelism has nothing to do. `KheopsCommand`
therefore uses `Math.min(nThreads, 8)` - on the plateau, with some headroom for
the multi series case where several exports in parallel share one pool, and far
cheaper in memory than one Bio-Formats reader per worker thread.

Reproduce with `ExportBenchmark poolsweep`.

### LIF, series 0

1024x1024 with a single resolution level: too small to show anything but fixed
overhead (`no pyramid` equals the baseline because there is no pyramid). Only
`uncompressed` moves, at x1.35.

### Compression codecs, VSI `10x_01`, reader pool already raised

| codec | median | file size |
| --- | ---: | ---: |
| LZW (current default) | 6879 ms | 187 MB |
| zlib / Deflate | 27313 ms | 162 MB |
| Uncompressed | 4937 ms | 343 MB |

### Parallel temporary writer (issue #12)

Every tile of a level below the last is written twice: to the temporary file the
next level is downsampled from, and to the final file. Both writes used to run
one after the other on the single writing thread. Timing them separately, on a
warmed-up export with the reader pool already raised:

| dataset | total | temp save | main save | waiting for tiles |
| --- | ---: | ---: | ---: | ---: |
| in memory, 8000x6000 x 3 ch | 6338 ms | 1559 ms (25%) | 4304 ms (68%) | 5% |
| VSI `10x_01` | 6514 ms | 1406 ms (22%) | 4250 ms (65%) | 9% |
| VSI RGB overview | 7473 ms | 1383 ms (19%) | 4854 ms (65%) | 11% |
| CZI series 0, single level | 2066 ms | - | 2000 ms (97%) | 0% |

The two writes are **not** symmetric, which is why the ceiling is not the factor
2 the issue assumed: the final writer compresses (LZW) and keeps the pyramid's
SubIFD bookkeeping, the temporary one writes raw bytes. At a ratio of ~3:1,
`(a+b)/max(a,b)` is ~1.3. Low "waiting for tiles" is what makes the saving
realisable: the writing thread is saturated, the workers are not the limit.

Handing the temporary write to one thread with a short queue
(`OMETiffExporter.AsyncTileWriter`), measured end to end, 1 warmup + 3 repeats:

| dataset | before | after | speedup |
| --- | ---: | ---: | ---: |
| in memory, 8000x6000 x 3 ch | 5370 ms | 4231 ms | **x1.27** |
| VSI `10x_01` | 5434 ms | 4405 ms | **x1.23** |
| VSI RGB overview | 6573 ms | 5589 ms | **x1.18** |
| CZI series 0, single level | 1826 ms | 1829 ms | x1.00 |
| LIF series 0, single level | 345 ms | 354 ms | x0.97 |

A single resolution level has no temporary file, so it gains nothing - the last
two rows are noise, and are there to show the path is untouched. The pixels of
every plane of every resolution level hash identically before and after on all
five datasets.

Do not expect this to hold on a spinning disk or on network storage: the two
streams then share the bandwidth they have here in surplus.

### `AverageImageScaler.downsample`, 2048x2048 to 1024x1024

| pixels | per tile | throughput |
| --- | ---: | ---: |
| uint8, 1 channel | 24 ms | 164 MB/s |
| uint16, 1 channel | 39 ms | 204 MB/s |
| float32, 1 channel | 42 ms | 381 MB/s |
| uint8, 3 channels planar | 100 ms | 120 MB/s |
| uint8, 3 channels interleaved | 80 ms | 150 MB/s |

## What the numbers mean

**Today, real files are decode bound; synthetic ones are writer bound.**
`KheopsCommand` opens the source with a pool of a *single* Bio-Formats reader
(`nParallelJobs = 1`), while the exporter runs one worker thread per processor.
All 31 threads therefore queue on that one reader while computing resolution
level 0. Raising the pool is worth **x1.84 to x2.59** on the VSI slide and
nothing at all on an uncompressed synthetic file - which is the control that
identifies the effect as decoding parallelism rather than metadata reading.

Only resolution level 0 is affected. `OMETiffExporter.computeTile` branches:

- `r == 0` reads from the `Source` objects through `getBytesFromRAIs`, so it
  goes through the cached cell img and therefore through the reader pool;
- `r > 0` reads the temporary file of the previous level through an
  `OMETiffReader` that **each thread creates for itself**, which never touches
  the pool.

Level 0 is about 75 % of the pixels of a pyramid, which is why the pool still
dominates the total.

The decisive experiment is `no pyramid + reader pool`, which switches the
pyramid off entirely - no temporary files, no `OMETiffReader`, no scaling - so
that only the level 0 path remains. The pool is still worth **x2.45** on the
16 bit series (9242 -> 3774 ms) and **x3.87** on the RGB one (17447 -> 4511 ms).
That rules out metadata reading, which is identical in both configurations and
costs about 1 s in total.

**Once the pool is raised, everything converges on the single writing thread.**
Both VSI series land at ~7.5 s, the same ceiling the synthetic tests show. The
worker threads then have nothing left to contribute: in the in-memory test one
worker thread is as fast as 31.

**The pyramid overhead is partly hidden today.** Subtracting the `no pyramid`
row from the corresponding full pyramid one gives what building the pyramid
costs:

| series | at pool 1 | at pool 31 |
| --- | ---: | ---: |
| VSI `10x_01` | 2815 ms | 2793 ms |
| VSI RGB overview | 1877 ms | 2961 ms |

For the 16 bit series it is the same either way, as expected since the pyramid
stage never touches the reader pool. For the RGB one it grows by ~1.1 s once the
pool is raised: the temporary file is written on the writing thread, which sits
idle waiting for tiles while decoding is the bottleneck, so part of that cost is
free today and only becomes visible once decoding is fixed. The two problems
therefore compose, but not purely additively - do not simply add the speedups.

In the in-memory test, where there is no decoding at all, the overhead is at its
clearest: writing level 0 only takes 3459 ms of the 6787 ms baseline, although
the pyramid adds just ~33 % more pixels. At the efficiency of level 0 the whole
pyramid should cost ~4.6 s, so roughly **2 s, about a third of the export**, is
the temporary file round trip - every level is written a second time, reopened,
and read back tile by tile.

**Half of that round trip is now overlapped rather than removed.** The temporary
write, 19-25 % of the export, runs on its own thread since issue #12, worth
x1.18 to x1.27 (table above). What is left of the round trip is the reopening and
the tile by tile read back, both on the worker threads, which are not the
bottleneck. Removing the round trip altogether means storing the *downsampled*
level in the temporary file instead of a copy of the current one - four times
less temporary data - which costs a full width band of the next level in RAM,
`width x tileY x bytesPerPixel` at the peak, ~100 MB for a 100 000 px wide uint16
plane. That is a rewrite of the pyramid path for ~1.2x on top of what is already
gained, so it is not obviously worth it.

**Compression is ~46 % of the writer bound time**, but no better codec is
available: zlib is four times slower than LZW for 13 % smaller files. LZW should
stay the default.

**Two things that look promising in the source are not.** Reporting progress
once per tile costs ~1 %: at 1024 px tiles a realistic image has hundreds to
tens of thousands of tiles, not millions. And `AverageImageScaler`, despite
allocating `scale^2` intermediate buffers and calling `System.arraycopy` once
per pixel, is hidden behind the writer - optimising it buys nothing in the
default configuration.

### Priorities this suggests

1. ~~Raise the source reader pool size.~~ **Done**: `KheopsCommand` now uses
   `Math.min(nThreads, 8)` readers instead of 1.
2. ~~Overlap the two writes of a tile.~~ **Done**: the temporary file is written
   on its own thread, `OMETiffExporter.AsyncTileWriter`, x1.18 to x1.27 on a
   pyramid and nothing on a single level export (issue #12).
3. Remove what is left of the temporary file round trip, by storing the
   downsampled level rather than a copy of the current one. ~1.2x on top, a real
   rewrite, and it puts a full width band of the next level in RAM.
4. Leave the codec, the progress reporting and the scaler alone, performance
   wise. The scaler still needs its float bug fixed, for correctness.

## A deadlock found along the way

With a reader pool of 1 - the value `KheopsCommand` uses - opening a multi
series CZI **hung forever**. `BioFormatsOpener.ReaderPool` built its `model`
reader with `this.acquire()`; that model is only a template for `createObject()`
and is never handed back, so it permanently consumed one permit. A pool of size
1 was left with no usable reader at all and every later `acquire()` blocked on
an empty queue.

Fixed in `bigdataviewer-image-loaders` by creating the model with
`readerSupplier.get()` instead. Verified: the same file went from an infinite
hang to 978 ms at pool size 1.

`ExportBenchmark` runs each configuration under a watchdog so that a deadlock is
reported and skipped rather than blocking the whole run.

## Defects the unit tests pin

`mvn test` runs 31 tests in a few seconds, all synthetic, no download.
Four of them fail against the current code, and each failure is a real defect:

| test | defect |
| --- | --- |
| `AverageImageScalerTest.downsample32BitsFloatGray` | every float sample is truncated to `int` before averaging, and the accumulator is an `int` |
| `OMETiffExporterTest.floatPyramidLevelsAreDownsampled` | the same bug end to end: float pyramid levels are silently corrupted |
| `AverageImageScalerTest.downsample16BitsLittleEndian` | the `littleEndian` flag is ignored (dormant: the exporter always writes big endian) |
| `AverageImageScalerTest.downsampleSizeNotMultipleOfScaleFactor` | with a scale factor >= 3 and a tile size far from a multiple of it, the scaler spreads its sampling instead of averaging contiguous blocks (unreachable at the default downsample of 2) |

## Caveats

- Configurations run in sequence, so later ones may benefit from a warmer OS
  file cache. One 10 % artefact was produced this way and disproved by the next
  dataset. Treat differences below ~10 % as noise unless a second dataset
  agrees.
- The `vs baseline` column of `ScalerBenchmark` is suppressed: its rows use
  different pixel types and are not comparable.
- `no pyramid` deliberately does less work than the other configurations; it is
  a breakdown, not a speedup.
- Local builds need `-Denforcer.skip=true`: the enforcer bans the locally
  installed `ch.epfl.biop` SNAPSHOT jars for their bytecode level.
