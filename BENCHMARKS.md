# Benchmarks

Measurements of the pyramidal OME-TIFF export, and the reasoning they support.
The code lives in `src/test/java/ch/epfl/biop/kheops/bench/`. These are plain
`main()` classes, **not** JUnit tests: `mvn test` does not run them.

## Why they exist

Reading the exporter suggests several plausible bottlenecks. Most of them turned
out to be wrong. The numbers below exist so that the next optimisation attempt
starts from evidence rather than from intuition.

## Running them

The generated datasets total about 12 GB at their default sizes, chosen so that
a default export of each takes on the order of ten seconds. `kheops.bench.giantSize`,
`kheops.bench.seriesSize`, `kheops.bench.planeSize` and `kheops.bench.timepoints`
scale them down if that is too much.

The real datasets are downloaded once into `~/CachedSamples` (via `DatasetHelper`
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
`lif` (all of them if none is given), the opt in `giant`, `manyplanes` and
`multiseries`, `standard` (see below), and `list` to print the series of each
file. Results are printed as a table and written as CSV
to `target/benchmark-results/`.

Two system properties decide where the bytes go, and neither defaults to a good
place on a machine whose system drive is small:

| property | what it holds | default |
| --- | --- | --- |
| `kheops.bench.inputDir` | the generated synthetic sources | `~/CachedSamples/kheops-bench` |
| `kheops.bench.outputDir` | every export, plus its temporary pyramid file | `%TEMP%/kheops-bench-output` |

The output directory takes by far the most traffic: each measured run writes the
pyramid and its temporary file there and deletes them again. Put it on the drive
whose speed you actually mean to measure.

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
| `memory` | 22000x16000, 3 ch, uint16, built in RAM | the writing side only, no decoding at all |
| `synthetic` | the same, as an uncompressed tiled OME-TIFF (2.1 GB raw, 5 levels) | the full pipeline with a nearly free decoder |
| `giant` | one uint16 plane of 32000x32000 (1.9 GB raw, 6 levels), generated | a deep pyramid with no per-plane overhead at all |
| `multiseries` | 16 series of 8192x8192 uint16 (2 GB raw, 4 levels each), generated | the threading `KheopsCommand` really uses on a multi-series file |
| `manyplanes` | 1024x1024 uint16, 1250 t x 2 ch (2500 planes, 4.9 GB raw, 1 level), generated | per-plane overhead: one TIFF directory per plane, no pyramid at all |
| `vsi` | Olympus VSI slide, series 2 `10x_01`, 7230x6151, 3 ch uint16 | the archetypal Kheops input, expensive JPEG decoding |
| `vsirgb` | the RGB overview of the same slide, 16172x6817 | the ARGB / interleaved path |
| `czi` | Zeiss CZI, 4 series, 1500x1000, 41 z ([Zenodo 19047136](https://zenodo.org/records/19047136)) | an expensive decoder, many planes, no pyramid |
| `lif` | Leica LIF, 9 series, 1024x1024, 3 ch, ~9 z ([Zenodo 13773035](https://zenodo.org/records/13773035)) | C/Z loops; too small to measure much else |

The VSI slide is slide 0 of [Zenodo 6553641](https://zenodo.org/records/6553641).
Comparing `synthetic` with `vsi` is what separates a decoding bottleneck from a
writing one: they export the same amount of data, through the same code, and
differ only in how expensive the source is to read.

`giant` is opt in - `ExportBenchmark` does not run it unless it is named on the
command line - because it generates a 488 MB source and takes a few times
longer than the other datasets. Its source is written one tile at
a time (`SyntheticImages.writeTiles`), so the scenario is not bounded by what a
`byte[]` can hold: raise `-Dkheops.bench.giantSize` past 32404 and the plane no
longer fits in one, which no plane-at-a-time generator could produce. Its
side is deliberately not a multiple of the 1024 pixel tile, so the clipped
tiles of the right and bottom edges are exercised as well.

### Why `multiseries` exists

Every other dataset exports **one** series with **all** the worker threads. That
is what `KheopsCommand` does only when a file holds a single series. As soon as
there is more than one, `process_series_in_parallel` is set
(`KheopsCommand.java:165`) and each series is exported by **one** worker thread
with a queue of 4 tiles instead of 64 (`KheopsCommand.java:291-293`), with the
series running concurrently. `KheopsBatchCommand` does the same, `nThreads(1)`
per file (`KheopsBatchCommand.java:238`), at tile size 512.

Since the archetypal Kheops input - a slide scanner file, a LIF, a CZI - holds
several series, that is the path most real exports take, and until this dataset
existed nothing measured it. The `1 worker thread` row of the other datasets is
not a substitute: it runs a single lonely export on an otherwise idle machine,
whereas the real path runs as many concurrent single-worker exports as there are
processors.

## Results

Java 21.0.11, Windows 11, 32 logical processors, 12 GB heap, NVMe.
1 warmup + 3 repeats, median reported. Baseline = what `KheopsCommand` does
today: 1024 px tiles, LZW, 31 worker threads,
**a source reader pool of 1**, progress monitor on.

> Note: that baseline label is stale. `Config.readerPoolSize` still defaults to
> 1, but `KheopsCommand.java:133` has used `Math.min(nThreads, 8)` since the
> pool fix, so the `baseline` rows below understate what Kheops does today on
> any dataset where decoding costs something. The standard mode table just
> below uses the real default of 8.

**The `synthetic`, `vsirgb`, `czi` and `lif` tables below were measured before
the parallel temporary writer and the pre-compressed tiles**, and are kept for
the reasoning they support, not as current timings. The in-memory and VSI
`10x_01` tables have been re-measured since. The two dedicated sections further
down carry the before/after of each change.

### Standard mode: what a user actually gets

`ExportBenchmark standard`. Nothing is varied: this is the throughput of the
defaults, LZW, on each synthetic dataset. Output on `F:`, an NVMe SSD.

| dataset | raw input | levels | median | MB/s | **MPix/s, level 0** | **MPix/s, all levels** |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `synthetic`, 22000x16000, 3 ch | 2014 MB | 5 | 9482 ms | 212.4 | **111.4** | **148.3** |
| `giant`, one 32000x32000 plane | 1953 MB | 6 | 9386 ms | 208.1 | **109.1** | **145.4** |
| `giant`, level 0 only, no pyramid | 1953 MB | 1 | 3148 ms | 620.5 | **325.3** | **325.3** |
| `manyplanes`, 1024x1024, 1250 t x 2 ch | 5000 MB | 1 | 10750 ms | 465.1 | **243.9** | **243.9** |
| `multiseries`, 16 x 8192x8192 | 2048 MB | 4 | 10528 ms | 194.5 | **102.0** | **135.5** |

The datasets are sized so that a default export of each takes on the order of
ten seconds, which is long enough that startup and the first tiles do not
dominate. The level 0 row is the exception: it shares the `giant` input and
does a sixth of the work, so it lands at three seconds.

The defaults are: LZW, 1024 pixel tiles, 31 worker threads, a reader pool of
`min(nThreads, 8)` - so 31 workers and **8** readers - a queue of 64 tiles, and
a progress monitor. A file holding several series is the exception: it takes the
parallel path `KheopsCommand` switches to on its own, one worker per series,
queue of 4, series exported concurrently.

**Read the MPix/s columns, not MB/s.** MB/s counts the input, and the datasets
neither share a bytes-per-pixel nor build the same pyramid. A 1024 pixel plane
is not larger than the 1024 pixel tile, so `manyplanes` gets a single resolution
level: no downsampling, no temporary file, no second write. It leads on MB/s
because it does the least work per input megabyte, not because per plane
overhead is cheap.

On the all-levels column, which counts every pixel the exporter computes and
writes, the four real datasets land within 10 % of each other: 148, 145, 244 and
136 MPix/s. Shape matters much less than it looks like it does from MB/s, once
the pyramid is accounted for. `manyplanes` is genuinely ahead, and it is the one
that builds no pyramid at all.

**The pyramid costs a factor of 2.2.** The same 32000x32000 plane exports at
109 MPix/s with its six levels and 325 MPix/s with level 0 alone. That row is a
ceiling on the writing path, not a configuration anyone converts with: it skips
the temporary file, the downsampling, the second write, and the level barrier
described under the giant plane results.

**None of these are compressed bytes.** The LZW output size is not measured
here - the benchmark deletes each export as soon as it is timed. Wall time and
output size per codec are what `CompressionBenchmark` reports.

**Precision.** Both passes of this table were measured in one JVM and agree to
within 3 % on every row. Against a run of the same table half an hour earlier
the rows differ by 2 to 10 %. That is much tighter than the 13 to 19 % seen at
the previous, smaller sizes, which fits the ten second target doing its job -
the shorter the export, the more a fixed cost or a warm cache moves it. Compare
rows only within one table; re-measure the whole table in one JVM before
comparing it with another.

### In memory, 8000x6000 x 3 ch uint16 (275 MB raw, 4 levels)

Re-measured after both changes. The reader pool is irrelevant here: no decoding.

| configuration | median | vs baseline |
| --- | ---: | ---: |
| baseline | 1954 ms | - |
| no progress monitor | 1914 ms | x1.02 |
| uncompressed | 1858 ms | x1.05 |
| writer compresses | 4204 ms | x0.46 |
| 1 worker thread | 4629 ms | x0.42 |
| no pyramid (level 0 only) | 372 ms | x5.26 |

`uncompressed` is now worth almost nothing (x1.05) because the compression no
longer runs on the writing thread. `1 worker thread` fell to x0.42 for the same
reason: the workers compress now, so their number matters.

### Synthetic file, same size (nearly free decoding)

| configuration | median | vs baseline |
| --- | ---: | ---: |
| baseline | 7029 ms | - |
| reader pool = threads | 7188 ms | x0.98 |
| uncompressed | 4238 ms | x1.66 |
| 1 worker thread | 7953 ms | x0.88 |
| no pyramid (level 0 only) | 4135 ms | x1.70 |

### VSI `10x_01`, 7230x6151 x 3 ch uint16 (real decoding)

Re-measured after both changes.

| configuration | median | vs baseline |
| --- | ---: | ---: |
| baseline | 9389 ms | - |
| **reader pool = threads** | **2415 ms** | **x3.89** |
| no progress monitor | 9146 ms | x1.03 |
| uncompressed | 9025 ms | x1.04 |
| writer compresses | 10042 ms | x0.93 |
| 1 worker thread | 12676 ms | x0.74 |
| reader pool + no monitor | 2427 ms | x3.87 |
| no pyramid (level 0 only) | 8137 ms | x1.15 |
| **no pyramid + reader pool** | **1420 ms** | **x6.61** |

The baseline row still uses a reader pool of 1, which `KheopsCommand` no longer
does: it is kept as the reference the other rows are relative to. At pool 1 the
export is decode bound, which is why `writer compresses` barely shows here
(x0.93) while it is x2.28 in memory - the compression it moves was hidden behind
decoding. Compare `reader pool = threads` (2415 ms) with the 6567 ms the same
configuration cost before the two changes.

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

### Pre-compressed tiles

Bio-Formats writers implement `loci.formats.ICompressedTileWriter`: `getCodec()`
hands out the codec and `saveCompressedBytes` takes a tile the caller already
compressed. The workers therefore compress, and the writing thread only writes.
Same 1 warmup + 3 repeats, LZW, reader pool 8:

| dataset | writer compresses | workers compress | speedup |
| --- | ---: | ---: | ---: |
| in memory, 8000x6000 x 3 uint16 | 4244 ms | 1860 ms | **x2.28** |
| VSI `10x_01` | 4493 ms | 2383 ms | **x1.89** |
| VSI RGB overview | 5733 ms | 4695 ms | **x1.22** |
| CZI series 0, single level | 1847 ms | 320 ms | **x5.77** |
| LIF series 0, single level | 359 ms | 147 ms | **x2.44** |

Pixels hash identically both ways on all five datasets, and on the CZI every
IFD - tile geometry, byte counts **and** offsets - is identical, so the
compressed bytes are the same and land in the same place.

Two effects, not one. LZW itself is ~40 % of a writer bound export: in memory,
4244 ms with LZW against 2505 ms uncompressed. The rest is an accident of
`TiffSaver.writeImage`: its bulk copy branch needs
`tileH * tileW * channels * bpp == buf.length`, which an **edge tile never
satisfies**, so a partial tile falls into a triple loop that copies and zero pads
it one `writeByte` at a time, on the writing thread. That is why the CZI wins
most: 1500x1000 with 752x1008 tiles means *every* tile is a partial one. Padding
with `System.arraycopy` on a worker instead is worth more there than the codec.

**RGB above resolution level 0 is excluded.** `saveCompressedBytes` writes one
strip, and the exporter turns interleaving off above level 0, where TIFF then
wants one strip per sample (planar configuration 2). Written as a single strip,
such a tile yields an IFD claiming three tiles at offsets 0 - silently corrupt,
and caught by `rgbMultiDimensionalExportIsValid`. Those levels keep compressing
on the writing thread, which is why the RGB overview gains least; level 0, ~75 %
of the pixels, still goes the fast way.

What a pre-compressed tile must contain is not documented by the interface - it
is whatever `TiffSaver.writeImage` would have built. `-Dkheops.precompress=false`
gives the compression back to the writer if a future Bio-Formats diverges.
Reproduce with the `writer compresses` row of `ExportBenchmark`.

### Reusing the compressed tile for the temporary file

A tile of a level below the last is written to two files. It used to be
serialized twice: compressed for the final file, and raw for the temporary one,
through `saveBytes` - strip assembly, edge padding, IFD, full raw sized write.
The temporary writer takes the **same** compressed buffer now, so a tile is
serialized once and the identical bytes go to both files. The temporary file
declares the export's compression and is ~40 % smaller; the workers pay for
decompressing it when they read the level back.

Measured back to back, alternating the two builds in one session:

| dataset | serialized twice | serialized once | speedup |
| --- | ---: | ---: | ---: |
| in memory, 8000x6000 x 3 uint16 | 2132 ms | 1851 ms | **x1.15** |
| VSI `10x_01` | 2702 ms | 2665 ms | x1.01 |
| VSI RGB overview | 5439 ms | 5228 ms | x1.04 |

**It did what it was meant to, and the ceiling moved.** In memory, the share of
the export the writing thread spent blocked on the temporary writer went from
**54 % to 7 %** (42 % + 12 % draining, down to 5.8 % + 1.2 %). The temporary
writer is no longer the bottleneck at all. The total only fell 15 % because the
time reappeared on the other side: waiting for the worker threads went from 20 %
to **63 %**. The workers now downsample, compress *and* decompress the level they
read back, and they are what everything queues behind.

**Anything measured across builds from here has to alternate them in one
session.** Over this session the same code on the same VSI series drifted from
2383 ms to 2665 ms, ~12 %, which is larger than any of the differences in the
table above. Two of those rows read as regressions when compared against numbers
taken an hour earlier, and are not.

`compressTemporaryFiles` is gone. It let the caller compress the temporary
files with LZW to save disk space, at the cost of compressing every tile twice.
The tile is compressed once now and the temporary file gets those bytes, so the
option had nothing left to choose.

### `AverageImageScaler.downsample`, 2048x2048 to 1024x1024

| pixels | per tile | throughput |
| --- | ---: | ---: |
| uint8, 1 channel | 24 ms | 164 MB/s |
| uint16, 1 channel | 39 ms | 204 MB/s |
| float32, 1 channel | 42 ms | 381 MB/s |
| uint8, 3 channels planar | 100 ms | 120 MB/s |
| uint8, 3 channels interleaved | 80 ms | 150 MB/s |


### Synthetic scenarios, output on a second drive

The two tables below were measured with `kheops.bench.outputDir` on `F:`, unlike
every table above it, which used `%TEMP%` on `C:`. On this machine that costs
about 20 %: the same in-memory export takes 1592 ms writing to `C:` and 1908 ms
writing to `F:`. Rows **within** each table are comparable; rows across the two
drives are not.

#### One giant plane, 16000x16000 uint16 (488 MB raw, 5 levels)

> Measured at the earlier, smaller default size. `giant` is 32000x32000 today,
> so these timings are not comparable with the standard mode table above; the
> ratios between the rows still are.

| configuration | median | MB/s | vs baseline |
| --- | ---: | ---: | ---: |
| baseline | 3671 ms | 133.0 | - |
| reader pool = threads | 3226 ms | 151.3 | x1.14 |
| no progress monitor | 3777 ms | 129.3 | x0.97 |
| uncompressed | 2139 ms | 228.3 | x1.72 |
| writer compresses | 7046 ms | 69.3 | x0.52 |
| 1 worker thread | 27066 ms | 18.0 | x0.14 |
| reader pool + no monitor | 2703 ms | 180.7 | x1.36 |
| no pyramid (level 0 only) | 1141 ms | 427.8 | x3.22 |
| no pyramid + reader pool | 837 ms | 583.3 | x4.38 |

One plane instead of three reaches 133 MB/s against the 118.5 MB/s of
`synthetic`, and the reader pool matters more (x1.36 combined against x1.12):
a single large plane gives the pool more contiguous work to hand out.

The pyramid tail is the ceiling here. Levels are written strictly one after the
other - workers block on `currentLevelWritten` at `OMETiffExporter.java:527`
until the writing thread publishes the next level - and each level holds four
times fewer tiles than the one below: 256, 64, 16, **4**, 1. So level 3 can keep
exactly four threads busy on a 32 thread machine, whatever the configuration.
256 of the 341 tiles are level 0, so the tail is a quarter of the tiles running
at a fraction of the parallelism, which is what `no pyramid` being x3.22 is
measuring.

#### Multi series, 16 x 4096x4096 uint16 (512 MB raw, 3 levels each)

> Measured at the earlier, smaller default size. `multiseries` is 16 x 8192x8192
> today, so these timings are not comparable with the standard mode table above;
> the ratios between the rows still are.

Measured twice, in two JVMs. The second run added the combined configurations
and is the one to read; both are shown because the difference between them is
itself a result.

| configuration | run 1 | run 2 | vs first, run 2 |
| --- | ---: | ---: | ---: |
| parallel series, 1 worker (as `KheopsCommand`) | 2692 ms | 2272 ms | - |
| parallel series, 1 worker, queue 64 | 2615 ms | 2233 ms | x1.02 |
| parallel series, 2 workers | 2289 ms | 2063 ms | **x1.10** |
| serial series, all workers | 11003 ms | 8875 ms | **x0.26** |
| parallel series, 1 worker, pool = threads | 2249 ms | 2270 ms | x1.00 |
| parallel series, 1 worker, uncompressed | 1159 ms | 1136 ms | x2.00 |
| parallel series, 2 workers, pool = threads | - | 2034 ms | x1.12 |
| parallel series, 2 workers, pool = threads, queue 64 | - | 2068 ms | x1.10 |
| parallel series, 4 workers, pool = threads, queue 64 | - | 2123 ms | x1.07 |

**The current strategy is decisively right.** Exporting the series concurrently
with one worker each beats exporting them one at a time with every worker by
x3.9, in both runs. Giving each series a single thread is not a compromise, it
is close to the fastest arrangement measured, and at 225 MB/s this is the
highest throughput of any dataset here - higher than a single series export
using all 31 workers.

That also settles what the `1 worker thread` row of the other datasets means: it
measures one lonely export on an idle machine, and nothing more. On the path
that actually uses one worker per export, the parallelism comes from the series
and the machine stays busy.

What survives both runs:

- **Two workers per series is worth about x1.10**, and it is the only knob here
  that does anything. Four workers is worse (x1.07): at 16 series the machine
  already runs 16 writing threads and 16 temporary writers, so the workers are
  competing for cores well before that.
- **The reader pool does nothing on this path** (x1.00), and it does not
  compound with the worker change: 2 workers alone is x1.10, 2 workers with a
  pool of 31 is x1.12, inside the noise of each other. Level 0 is the only level
  read through the pool, and with 16 exports in flight there is already enough
  concurrency to hide it.
- **The queue depth does nothing** (x1.02), before or after the pool is raised.
  Dropping from 64 tiles to 4, which `KheopsCommand` does to keep the memory
  footprint of a parallel export down, is free.
- **The export is compression bound**, not I/O bound: dropping the codec halves
  the time (x2.00).

**A caution about run 1.** Its first configuration measured 2692 ms against
2272 ms for the same configuration in run 2, an 18 % gap, while every other row
moved far less. Reading run 1 alone made the reader pool look like a x1.20 win;
it is x1.00. The first configuration of a run pays a cost the later ones do not
- a cold read of the 512 MB source, JIT compilation, or both - and one warmup
run is not enough to absorb it on a two second workload. `vs first` is therefore
biased in favour of whatever runs later, and a difference under ~20 % between
the first row and any other row should not be trusted from a single run. This is
the ordering caveat at the end of this file, larger than it looks.

## What the numbers mean

**Today, real files are decode bound; synthetic ones are writer bound.**
`KheopsCommand` opens the source with a pool of a *single* Bio-Formats reader
(`nParallelJobs = 1`), while the exporter runs one worker thread per processor.
All 31 threads therefore queue on that one reader while computing resolution
level 0. Raising the pool is worth **x3.89** on the VSI 16 bit series today, and
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
that only the level 0 path remains. The pool is still worth **x5.73** on the
16 bit series (8137 -> 1420 ms) and x3.87 on the RGB one (pre-change numbers).
That rules out metadata reading, which is identical in both configurations and
costs about 1 s in total.

**The writing thread is no longer the single ceiling.** It was: before the two
changes below, both VSI series and the synthetic file all landed at ~7.5 s, and
one worker thread was as fast as 31. Now that the workers compress, they carry
real work again - in memory, one worker thread costs x0.42 against 31.

**What the pyramid costs.** Subtracting the `no pyramid` row from the full
pyramid one, both measured after the two changes:

| series | full pyramid | level 0 only | the pyramid |
| --- | ---: | ---: | ---: |
| in memory | 1954 ms | 372 ms | 1582 ms (81 %) |
| VSI `10x_01`, reader pool raised | 2415 ms | 1420 ms | 995 ms (41 %) |

A pyramid holds ~33 % more pixels than its level 0, so at the efficiency of
level 0 it should cost ~124 ms more in memory and ~470 ms more on the VSI. The
rest - **~1.45 s in memory, ~0.5 s on the VSI** - is the temporary file round
trip: every level below the last is written a second time, reopened, and read
back tile by tile.

Those two fractions, 81 % and 41 %, are far apart on purpose. The in-memory test
has no source decoding at all, so its level 0 is nearly free and the round trip,
which does decode a real TIFF back, dominates what is left. A real file pays for
decoding at level 0 too. **Quote the VSI figure for real inputs, not the
in-memory one.**

**Half of the round trip is overlapped rather than removed.** The temporary write
runs on its own thread since issue #12. What is left is the reopening and the
tile by tile read back, on the worker threads. Removing it altogether means
storing the *downsampled* level in the temporary file instead of a copy of the
current one - four times less temporary data - which costs a full width band of
the next level in RAM, `width x tileY x bytesPerPixel` at the peak, ~100 MB for a
100 000 px wide uint16 plane. On the VSI that is ~x1.3, on a writer bound export
considerably more. It is a rewrite of the pyramid path; the ceiling is now high
enough that it is worth reconsidering, but it is still the most expensive item
on this list.

**Compression was ~46 % of the writer bound time**, and now runs on the worker
threads instead (see above). No better codec is available anyway: zlib is four
times slower than LZW for 13 % smaller files. LZW stays the default.

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
   downsampled level rather than a copy of the current one. ~x1.3 on the VSI and
   more on a writer bound export, but a real rewrite, and it puts a full width
   band of the next level in RAM.
4. ~~Take the compression off the writing thread.~~ **Done**: the workers hand
   the writer tiles they already compressed, x1.22 to x5.77.
5. ~~Stop serializing a tile twice.~~ **Done**: the temporary writer takes the
   same compressed buffer as the final one, x1.15 on a writer bound export. It
   dropped the writing thread's wait on the temporary writer from 54 % to 7 %.
6. **The worker threads are the new ceiling** - 63 % of an in-memory export is
   the writing thread waiting for them. That is where to look next, not at the
   writer.
7. Leave the codec choice, the progress reporting and the scaler alone,
   performance wise. The scaler still needs its float bug fixed, for correctness.

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
