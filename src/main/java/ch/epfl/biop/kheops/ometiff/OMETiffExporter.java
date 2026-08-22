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

package ch.epfl.biop.kheops.ometiff;

import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.kheops.CZTRange;
import ch.epfl.biop.kheops.KheopsHelper;
import loci.common.image.IImageScaler;
import loci.formats.MetadataTools;
import loci.formats.codec.Codec;
import loci.formats.codec.CodecOptions;
import loci.formats.in.OMETiffReader;
import loci.formats.meta.IMetadata;
import loci.formats.meta.IPyramidStore;
import loci.formats.out.OMETiffWriter;
import loci.formats.out.PyramidOMETiffWriter;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.display.ColorConverter;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import ome.codecs.CompressionType;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.units.quantity.Time;
import ome.units.unit.Unit;
import ome.xml.meta.MetadataConverter;
import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.enums.PixelType;
import ome.xml.model.primitives.Color;
import ome.xml.model.primitives.NonNegativeInteger;
import ome.xml.model.primitives.PositiveInteger;
import org.apache.commons.io.FilenameUtils;
import org.scijava.task.Task;
import org.scijava.task.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static ch.epfl.biop.kheops.ometiff.SourceToByteArray.validPixelType;

/**
 * Exports a structure of {@link RandomAccessibleInterval} into a OME-TIFF file,
 * potentially multiresolution. To build this structure and create the export, one should use the
 * {@link OMETiffExporterBuilder} builder, which validates the structure
 * and allows to set OME metadata.
 *
 *
 * @author Nicolas Chiaruttini, EPFL, 2022
 */

// See https://forum.image.sc/t/ome-tiff-saving-optimisation-reading-from-the-file-thats-being-written/65705
// for a discussion about pyramid optimisation -> in the end the file is written two times - one
// for the final ome tiff, and another one which contains the current resolution level, that will be used
// for building the next resolution level
// original script https://github.com/ome/bio-formats-examples/blob/master/src/main/java/GeneratePyramidResolutions.java
// RAAAAH https://forum.image.sc/t/save-ome-tiff-as-8-bit-rgb-for-qupath/61281/3
// TODO : modify scale Z pixel size with range subset ?
// The two writes happen in parallel, see AsyncTileWriter and issue #12

public class OMETiffExporter<T extends NumericType<T>> {

	private static final Logger logger = //new SystemLogger(OMETiffExporter.class); <- uncomment to debug
			LoggerFactory.getLogger(OMETiffExporter.class);

	// ------------ Data and metadata
	final Map<Integer, Map<Integer, RandomAccessibleInterval<T>>> ctToRAI;
	final IMetadata oriMetadata;
	final int oriMetaDataSeries;

	// ------------ Saving options
	final CZTRange range; // To save a subset of C Z or T
	final int nResolutionLevels;
	final int downsample;
	final String compression;
	final File file;
	final int nThreads;
	final int dstSeries = 0;

	// ----------- Information collected before the export
	long totalTiles;
	// final int nChannels;
	final T pixelInstance;
	final int width, height, sizeT, sizeC, sizeZ;
	final Map<Integer, Integer> mapResToWidth = new HashMap<>();
	final Map<Integer, Integer> mapResToHeight = new HashMap<>();
	final Map<Integer, Integer> resToNY = new HashMap<>();
	final Map<Integer, Integer> resToNX = new HashMap<>();
	final Map<Integer, Integer> resToTileX = new HashMap<>();
	final Map<Integer, Integer> resToTileY = new HashMap<>();
	final boolean tiled; // false if the user requested a non-positive tile size

	// TIFF tiles width and length have to be a multiple of 16
	static final int TILE_GRANULARITY = 16;

	/**
	 * How many tiles may wait to be written to the temporary file, see
	 * {@link AsyncTileWriter}. Each one is a tile array kept alive on top of
	 * {@code maxTilesInQueue}, and staying one tile ahead is enough, so this is
	 * small on purpose. Untiled exports use 1: there, a tile is a whole plane.
	 */
	static final int TEMP_WRITE_QUEUE_DEPTH = 4;

	final boolean isLittleEndian;
	final boolean isRGB;
	final boolean isInterleaved;
	final boolean isFloat;
	final int bytesPerPixel;

	/** Samples per pixel: a TIFF RGB tile carries its three samples in one tile */
	final int samplesPerPixel;

	/** How each resolution level has to be compressed, see {@link #codecOptions} */
	final Map<Integer, CodecOptions> resToCodecOptions = new HashMap<>();

	// ------------ Fields updated live during the saving
	final AtomicLong writtenTiles = new AtomicLong();
	final Map<TileIterator.IntsKey, byte[]> computedBlocks;
	/**
	 * The same tiles as {@code computedBlocks}, compressed by the worker threads.
	 * Empty when the writer compresses them itself, see {@link #tileCodec}
	 */
	final Map<TileIterator.IntsKey, byte[]> compressedBlocks;
	/**
	 * The codec the workers compress tiles with, or null to leave the
	 * compression to the writer. Set once, before the workers are started
	 */
	volatile Codec tileCodec;
	final TileIterator tileIterator;
	final Task writerTask;
	final Object tileLock = new Object();
	OMETiffWriter currentLevelWriter; // Main writer <- ran in principal thread
	final ThreadLocal<OMETiffReader> localReader = new ThreadLocal<>(); // One reader per thread
	final ThreadLocal<IImageScaler> localScaler = new ThreadLocal<>(); // One scaler per thread
	final ThreadLocal<Integer> localResolution = new ThreadLocal<>(); // Current resolution level of the thread
	volatile int currentLevelWritten = -1;
	volatile boolean isCanceled = false; // as its name indicates - triggered via this::cancelExport method

	protected OMETiffExporter(
			// Image data
			Map<Integer, Map<Integer, RandomAccessibleInterval<T>>> ctToRAI, // Image data
			// Image metadata
			IMetadata originalOmeMeta, int originalSeries,
			// Writing options, including czt optional subset
			OMETiffExporterBuilder.WriterOptions writerSettings) throws Exception {
		// Monitoring
		if (writerSettings.taskService != null) {
			this.writerTask = writerSettings.taskService.createTask("Writing: " + new File(writerSettings.path).getName());
			this.writerTask.setCancelCallBack(this::cancelExport);
		} else {
			this.writerTask = null;
		}

		// Collect data
		this.ctToRAI = ctToRAI;
		this.oriMetadata = originalOmeMeta;
		this.oriMetaDataSeries = originalSeries;

		// Collecting useful data before export
		RandomAccessibleInterval<T> model = ctToRAI.get(0).get(0);
		pixelInstance = model.getAt(0,0,0);

		isRGB = pixelInstance instanceof ARGBType;
		isInterleaved = oriMetadata.getPixelsInterleaved(oriMetaDataSeries);
		isLittleEndian = false;

		if (pixelInstance instanceof UnsignedShortType) {
			bytesPerPixel = 2;
			isFloat = false;
		}
		else if (pixelInstance instanceof UnsignedByteType) {
			bytesPerPixel = 1;
			isFloat = false;
		}
		else if (pixelInstance instanceof FloatType) {
			bytesPerPixel = 4;
			isFloat = true;
		}
		else if (pixelInstance instanceof ARGBType) {
			bytesPerPixel = 1;
			isFloat = false;
		} else {
			throw new UnsupportedOperationException("Unhandled pixel type class: " +
					pixelInstance.getClass().getName());
		}

		samplesPerPixel = isRGB ? 3 : 1;

		width = originalOmeMeta.getPixelsSizeX(originalSeries).getValue();
		height = originalOmeMeta.getPixelsSizeY(originalSeries).getValue();

		// In case a subset of the image is exported, the size in CZT
		// of the exported image will not be equal to the original input image
		int iniSizeZ = originalOmeMeta.getPixelsSizeZ(originalSeries).getValue();
		int iniSizeT = originalOmeMeta.getPixelsSizeT(originalSeries).getValue();
		int iniSizeC = originalOmeMeta.getPixelsSizeC(originalSeries).getValue();
		range = CZTRange.builder().setC(writerSettings.rangeC).setT(writerSettings.rangeT).setZ(writerSettings.rangeZ).get(isRGB ? 1:iniSizeC, iniSizeZ, iniSizeT);
		sizeC = range.getRangeC().size();
		sizeZ = range.getRangeZ().size();
		sizeT = range.getRangeT().size();
		logger.debug(writerSettings.path+" Exported image size: #C"+sizeC+"#Z"+sizeZ+"#T"+sizeT);

		// Precomputes sizes of the image pyramid
		mapResToWidth.put(0, width);
		mapResToHeight.put(0, height);

		for (int i = 0; i < writerSettings.nResolutions - 1; i++) {
			mapResToWidth.put(i + 1, (int) (width / Math.pow(writerSettings.downSample, i + 1)));
			mapResToHeight.put(i + 1, (int) (height / Math.pow(writerSettings.downSample, i + 1)));
		}

		// Saving options
		this.downsample = writerSettings.downSample;
		this.nResolutionLevels = writerSettings.nResolutions;
		this.file = new File(writerSettings.path);
		this.compression = writerSettings.compression;
		this.nThreads = writerSettings.nThreads;


		// A tile size which is not strictly positive means that the user does not
		// want any tiling: a whole plane is then written at once, and the export
		// progress is counted in planes, see
		// https://github.com/BIOP/ijp-kheops/issues/31
		this.tiled = writerSettings.tileX > 0 && writerSettings.tileY > 0;

		// The tile size is adapted to each resolution level, and the number of
		// tiles is counted along the way
		// some assertion : same dimensions for all nr and c and t
		for (int r = 0; r < writerSettings.nResolutions; r++) {
			int maxX = mapResToWidth.get(r);
			int maxY = mapResToHeight.get(r);
			// Without tiling, the single block covers the whole resolution level
			int tileSizeX = tiled ? adjustTileSize(writerSettings.tileX, maxX)
					: Math.max(maxX, 1);
			int tileSizeY = tiled ? adjustTileSize(writerSettings.tileY, maxY)
					: Math.max(maxY, 1);
			resToTileX.put(r, tileSizeX);
			resToTileY.put(r, tileSizeY);
			resToNX.put(r, (int) Math.ceil(maxX / (double) tileSizeX));
			resToNY.put(r, (int) Math.ceil(maxY / (double) tileSizeY));
		}

		// Every resolution level compresses with its own tile size
		for (int r = 0; r < writerSettings.nResolutions; r++) {
			resToCodecOptions.put(r, codecOptions(r));
		}

		// Initialise transient variables for exporting
		writtenTiles.set(0);
		tileIterator = new TileIterator(nResolutionLevels, sizeT, sizeC, sizeZ,
				resToNY, resToNX, writerSettings.maxTilesInQueue);
		computedBlocks = new ConcurrentHashMap<>(nThreads * 3 + 1); // should be enough for avoiding overlap of hash
		compressedBlocks = new ConcurrentHashMap<>(nThreads * 3 + 1);

	}

	/**
	 * The options a tile of this resolution level has to be compressed with, to
	 * be accepted by {@link OMETiffWriter#saveCompressedBytes}.
	 * <p>
	 * This mirrors what the writer does on its own thread when it compresses a
	 * tile itself: {@code TiffCompression.getCompressionCodecOptions} builds
	 * these from the IFD, then {@code TiffSaver.writeImage} overrides the width,
	 * the height and the channel count with the tile geometry. The planar
	 * configuration is never set by the writer and defaults to 1, so a tile is
	 * always one interleaved strip.
	 * <p>
	 * Getting any of this wrong does not fail loudly, it writes a corrupt tile -
	 * hence the pixel comparison in {@code BENCHMARKS.md}.
	 */
	private CodecOptions codecOptions(int r) {
		CodecOptions options = new CodecOptions(CodecOptions.getDefaultOptions());
		options.width = resToTileX.get(r);
		options.height = resToTileY.get(r);
		options.bitsPerSample = bytesPerPixel * 8;
		options.channels = samplesPerPixel;
		options.littleEndian = isLittleEndian;
		options.interleaved = true;
		options.signed = false;
		return options;
	}

	/**
	 * A TIFF tile is always fully written into the file, even if it extends
	 * beyond the image boundaries: the extra pixels are padded with zeros. Using
	 * a tile size bigger than necessary thus wastes disk space - this is
	 * particularly visible with uncompressed files, and with the small
	 * resolution levels of a pyramid, which can be much smaller than the tile
	 * size requested by the user, see
	 * <a href="https://github.com/BIOP/ijp-kheops/issues/22">issue #22</a>.
	 * <p>
	 * This method keeps the number of tiles that the requested tile size would
	 * give, but shrinks the tiles to the smallest size which still covers the
	 * image (a multiple of 16, as required by the TIFF specification).
	 *
	 * @param requestedTileSize tile size requested by the user, along one axis -
	 *          it has to be strictly positive, a non-positive size means that no
	 *          tiling is wanted at all and is handled by the caller
	 * @param imageSize size of the image along the same axis
	 * @return the tile size which is effectively used along this axis
	 */
	static int adjustTileSize(int requestedTileSize, int imageSize) {
		if (imageSize < 1) return TILE_GRANULARITY;
		// Same rounding as the one performed by loci.formats.out.TiffWriter, so
		// that the tile size effectively used by the writer is known here
		int maxTileSize = requestedTileSize < TILE_GRANULARITY ? TILE_GRANULARITY
				: Math.round((float) requestedTileSize / TILE_GRANULARITY) * TILE_GRANULARITY;
		int nTiles = (int) Math.ceil(imageSize / (double) maxTileSize);
		int tileSize = (int) Math.ceil(imageSize / (double) nTiles /
				TILE_GRANULARITY) * TILE_GRANULARITY;
		return Math.min(Math.max(tileSize, TILE_GRANULARITY), maxTileSize);
	}

	public void cancelExport() {
		isCanceled = true;
		while (!writerTask.isDone()) {
			// TODO check whether this avoid making multiple cancel press
			synchronized (tileLock) { // Notifies that a new resolution level is being written
				tileLock.notifyAll();
			}
		}
	}

	private byte[] getBytesFromRAIs(TileIterator.IntsKey key) {
		int r = key.array[0];
		int t = key.array[1];
		int c = key.array[2];
		int z = key.array[3];
		int y = key.array[4];
		int x = key.array[5];

		long tileX = resToTileX.get(r);
		long tileY = resToTileY.get(r);

		long startX = x * tileX;
		long startY = y * tileY;

		long endX = (x + 1) * (tileX);
		long endY = (y + 1) * (tileY);

		int	maxX = width; // Before it's the resolution level 0
		int	maxY = height;

		if (endX > maxX) endX = maxX;
		if (endY > maxY) endY = maxY;

		RandomAccessibleInterval<T> rai =
				ctToRAI.get(range.getRangeC()
						.get(c)).get(range.getRangeT().get(t));

		RandomAccessibleInterval<T> slice = Views.hyperSlice(rai, 2,
				range.getRangeZ().get(z));

		return SourceToByteArray.raiToByteArray(Views.interval(slice,
				new FinalInterval(new long[] { startX, startY }, new long[] { endX - 1,
						endY - 1 })), pixelInstance);
	}

	/**
	 * The codec the worker threads should compress tiles with, so that the
	 * writing thread only has to write them, through
	 * {@code loci.formats.ICompressedTileWriter}. Compressing is ~40 % of a
	 * writer bound export, and the workers are idle while the writing thread is
	 * the bottleneck.
	 * <p>
	 * What a pre-compressed tile has to contain is not spelled out by the
	 * interface: it is whatever {@code TiffSaver.writeImage} would have produced,
	 * which this class reproduces in {@link #pad} and {@link #codecOptions}. A
	 * future version of bio-formats could change that and corrupt the output
	 * silently, so {@code -Dkheops.precompress=false} switches the whole thing
	 * off and gives the compression back to the writer.
	 *
	 * @return the codec, or null to leave the compression to the writer
	 */
	private Codec precompressionCodec(OMETiffWriter writer) {
		// A pre-compressed tile has to be aligned on the tile grid, and
		// saveCompressedBytes computes x % tileSizeX: an untiled export, where a
		// tile is a whole plane and the tile size is 0, is out
		if (!tiled) return null;
		if (System.getProperty("kheops.precompress", "true").equals("false")) {
			logger.debug(file.getName() + " pre-compression disabled by property");
			return null;
		}
		try {
			// Null for a compression this version of bio-formats has no codec for
			return writer.getCodec();
		}
		catch (UnsupportedOperationException e) {
			logger.debug(file.getName() +
					" writes its own compressed tiles: no codec available (" + e
							.getMessage() + ")");
			return null;
		}
	}

	/**
	 * Publishes a tile computed by a worker thread. The compressed copy is
	 * stored first, so that a tile visible in {@code computedBlocks} - which is
	 * what the writing thread waits on - always has its compressed copy ready.
	 */
	private void publishTile(TileIterator.IntsKey key, byte[] tile)
			throws Exception {
		Codec codec = tileCodec;
		if (codec != null && precompressible(key.array[0])) {
			compressedBlocks.put(key, compressTile(key, tile, codec));
		}
		computedBlocks.put(key, tile);
	}

	/**
	 * Whether a tile of this resolution level may be handed over compressed.
	 * <p>
	 * {@code saveCompressedBytes} takes a single {@code byte[]}, and writes it as
	 * a single TIFF strip. A tile is one strip, except when the writer stores the
	 * samples of an RGB tile as three separate planes - planar configuration 2,
	 * one strip per sample. That is what happens above resolution level 0, where
	 * this exporter turns interleaving off, and at level 0 too if the source is
	 * not interleaved. Written as a single strip, such a tile produces an IFD
	 * claiming three tiles at offsets 0, which is silently corrupt.
	 * <p>
	 * Those levels keep compressing on the writing thread. For an RGB pyramid
	 * that still leaves level 0, which is ~75 % of the pixels.
	 */
	private boolean precompressible(int r) {
		return samplesPerPixel == 1 || (r == 0 && isInterleaved);
	}

	/**
	 * Compresses a tile the way the writer would, so that it can be handed over
	 * with {@link OMETiffWriter#saveCompressedBytes}. Runs on a worker thread:
	 * compressing is about 40 % of the export and the writing thread is the
	 * bottleneck, while the workers wait.
	 */
	private byte[] compressTile(TileIterator.IntsKey key, byte[] tile, Codec codec)
			throws Exception {
		int r = key.array[0];
		int fullTileX = resToTileX.get(r);
		int fullTileY = resToTileY.get(r);
		int startX = key.array[5] * fullTileX;
		int startY = key.array[4] * fullTileY;
		int tileWidth = Math.min(fullTileX, mapResToWidth.get(r) - startX);
		int tileHeight = Math.min(fullTileY, mapResToHeight.get(r) - startY);
		// A fresh copy per call: a codec is free to write into the options it is
		// given, and several workers compress at the same time
		CodecOptions options = new CodecOptions(resToCodecOptions.get(r));
		return codec.compress(pad(tile, tileWidth, tileHeight, fullTileX,
				fullTileY), options);
	}

	/**
	 * A TIFF tile is always stored full size, zero padded at the right and the
	 * bottom edge of the image. The writer pads a partial tile itself - one
	 * {@code writeByte} call per byte, on the writing thread - but a
	 * pre-compressed tile has to arrive padded already.
	 */
	private byte[] pad(byte[] tile, int tileWidth, int tileHeight, int fullTileX,
			int fullTileY) {
		if (tileWidth == fullTileX && tileHeight == fullTileY) return tile;
		int bytesPerSample = bytesPerPixel * samplesPerPixel;
		byte[] padded = new byte[fullTileX * fullTileY * bytesPerSample];
		int tileRowLength = tileWidth * bytesPerSample;
		int fullRowLength = fullTileX * bytesPerSample;
		for (int row = 0; row < tileHeight; row++) {
			System.arraycopy(tile, row * tileRowLength, padded, row * fullRowLength,
					tileRowLength);
		}
		return padded;
	}

	private void computeTile(TileIterator.IntsKey key) throws Exception {
		int r = key.array[0];
		int t = key.array[1];
		int c = key.array[2];
		int z = key.array[3];
		int y = key.array[4];
		int x = key.array[5];

		long tileX = resToTileX.get(r);
		long tileY = resToTileY.get(r);

		long startX = x * tileX;
		long startY = y * tileY;

		if (r == 0) {
			localResolution.set(r);
			publishTile(key, getBytesFromRAIs(key));
		}
		else {
			// Wait for the previous resolution level to be written !
			while ((r != currentLevelWritten)&&(!isCanceled)) {
				synchronized (tileLock) {
					tileLock.wait();
				}
			}
			if(!isCanceled) {
				if ((localResolution.get() == null) || (localResolution.get() != r)) {
					// Need to update the reader : we are now writing the next resolution
					// level
					if (localReader.get() != null) {
						// Closing the previous local reader
						localReader.get().close();
						logger.debug("Local reader of "+file.getName()+" closed.");
					} else {
						localScaler.set(new AverageImageScaler());
					}
					OMETiffReader reader = new OMETiffReader();
					IMetadata omeMeta = MetadataTools.createOMEXMLMetadata();
					reader.setMetadataStore(omeMeta);
					reader.setId(getFileName(r - 1));
					reader.setSeries(0);
					localReader.set(reader);
					localResolution.set(r);
				}

				int plane = t * sizeZ * sizeC + c * sizeZ + z;

				long effTileSizeX = tileX * downsample;
				if (((startX * downsample) + effTileSizeX) >= mapResToWidth.get(r - 1)) {
					effTileSizeX = mapResToWidth.get(r - 1) - (startX * downsample);
				}

				long effTileSizeY = tileY * downsample;
				if (((startY * downsample) + effTileSizeY) >= mapResToHeight.get(r - 1)) {
					effTileSizeY = mapResToHeight.get(r - 1) - (startY * downsample);
				}

				byte[] tileBytePreviousLevel = localReader.get().openBytes(plane,
						(int) (startX * downsample), (int) (startY * downsample),
						(int) (effTileSizeX), (int) (effTileSizeY));

				byte[] tileByte = localScaler.get().downsample(tileBytePreviousLevel,
						(int) effTileSizeX, (int) effTileSizeY, downsample, bytesPerPixel,
						isLittleEndian, isFloat, isRGB ? 3 : 1, false);

				publishTile(key, tileByte);
			}
		}
	}

	private boolean computeNextTile() throws Exception {
		TileIterator.IntsKey key = null;
		synchronized (tileIterator) {
			if (tileIterator.hasNext()) {
				key = tileIterator.next();
			}
		}
		if (key == null) {
			synchronized (tileLock) {
				tileLock.notifyAll();
			}
			if (localReader.get() != null) {
				localReader.get().close(); // Close last resolution
				logger.debug("Local reader of "+file.getName()+" closed.");
			}
			return false;
		} else if (isCanceled) {
			if (localReader.get() != null) {
				localReader.get().close(); // Close last resolution
				logger.debug(file.getAbsolutePath()+"\t Thread "+Thread.currentThread()+" local reader closed.");
			}
			return false;
		} else {
			computeTile(key);
			synchronized (tileLock) {
				tileLock.notifyAll();
			}
			return true;
		}
	}

	private void copyChannelsMeta(IMetadata metaDst, int seriesDst, IMetadata metaSrc, int seriesSrc) {
		if (isRGB) {
			MetadataConverter.convertChannels(metaSrc,seriesSrc,0,metaDst,seriesDst,0,true);
		} else for (int c = 0; c < sizeC; c++) {
			int srcC = range.getRangeC().get(c);
			MetadataConverter.convertChannels(metaSrc,seriesSrc,srcC,metaDst,seriesDst,c,true);
		}
	}

	public void export() throws Exception {
		// Routes the output through a handle that does not ask the OS to extend the
		// file on every write. Temporary, see ch.epfl.biop.kheops.ometiff.omecommon.
		try (ch.epfl.biop.kheops.ometiff.omecommon.FastOutput fastOutput =
				new ch.epfl.biop.kheops.ometiff.omecommon.FastOutput(file)) {
		// Writes the temporary file in parallel with the final one, see #12. Only
		// a pyramid has a temporary file at all
		AsyncTileWriter tempTileWriter = nResolutionLevels > 1
				? new AsyncTileWriter(file.getName(), tiled ? TEMP_WRITE_QUEUE_DEPTH : 1)
				: null;
		try { // try... finally statement -> makes sure to finish the task in case of errors
			if (writerTask != null) writerTask.setStatusMessage("Exporting " + file
					.getName() + " with " + nThreads + " threads.");
			// Copy metadata from source to dest
			IMetadata omeMeta = MetadataTools.createOMEXMLMetadata();
			IMetadata currentLevelOmeMeta = MetadataTools.createOMEXMLMetadata();

			MetadataTools.populateMetadata(omeMeta, dstSeries,
					oriMetadata.getImageName(oriMetaDataSeries), isLittleEndian,
					isRGB ? DimensionOrder.XYCZT.getValue(): DimensionOrder.XYZCT.getValue(),
					oriMetadata.getPixelsType(oriMetaDataSeries).toString(), width, height,
					range.getRangeZ().size(), isRGB ? 3 : range.getRangeC().size(), range.getRangeT().size(), oriMetadata.getChannelSamplesPerPixel(oriMetaDataSeries, 0).getValue());
			omeMeta.setPixelsInterleaved(oriMetadata.getPixelsInterleaved(oriMetaDataSeries), dstSeries);

			MetadataTools.verifyMinimumPopulated(omeMeta, dstSeries);

			MetadataTools.populateMetadata(currentLevelOmeMeta, dstSeries,
					oriMetadata.getImageName(oriMetaDataSeries), isLittleEndian,
					isRGB ? DimensionOrder.XYCZT.getValue(): DimensionOrder.XYZCT.getValue(),
					oriMetadata.getPixelsType(oriMetaDataSeries).toString(), width, height,
					range.getRangeZ().size(), isRGB ? 3 : range.getRangeC().size(), range.getRangeT().size(), oriMetadata.getChannelSamplesPerPixel(oriMetaDataSeries, 0).getValue());
			omeMeta.setPixelsInterleaved(oriMetadata.getPixelsInterleaved(oriMetaDataSeries), dstSeries);

			MetadataTools.verifyMinimumPopulated(currentLevelOmeMeta, dstSeries);

			KheopsHelper.transferSeriesMeta(oriMetadata, this.oriMetaDataSeries, omeMeta, this.dstSeries);
			MetadataConverter.convertMetadata(omeMeta, currentLevelOmeMeta);

			copyChannelsMeta(omeMeta, this.dstSeries, oriMetadata, this.oriMetaDataSeries);
			copyChannelsMeta(currentLevelOmeMeta, this.dstSeries, oriMetadata, this.oriMetaDataSeries);

			for (int r = 0; r < nResolutionLevels - 1; r++) {
				((IPyramidStore) omeMeta).setResolutionSizeX(new PositiveInteger(
						mapResToWidth.get(r + 1)), dstSeries, r + 1);
				((IPyramidStore) omeMeta).setResolutionSizeY(new PositiveInteger(
						mapResToHeight.get(r + 1)), dstSeries, r + 1);
			}

			// Setup main writer.
			// PyramidOMETiffWriter.close() reopens the output file twice per plane, to
			// fill in each plane's SubIFD array. With a single resolution level there is
			// no SubIFD to fill in and those two opens per plane are pure cost: on a
			// 4002-plane light sheet stack they were 39 s of a 78 s export. The plain
			// writer produces the same pixels and the same OME-XML, minus one empty
			// SubIFD tag per IFD.
			OMETiffWriter writer = nResolutionLevels > 1
					? new PyramidOMETiffWriter()
					: new OMETiffWriter();
			writer.setMetadataRetrieve(omeMeta);
			writer.setWriteSequentially(true); // Setting this to false can be problematic according to QuPath
			writer.setBigTiff(true);
			writer.setId(file.getAbsolutePath());
			writer.setSeries(dstSeries);
			writer.setCompression(compression);
			writer.setInterleaved(omeMeta.getPixelsInterleaved(dstSeries));
			// Compressing a tile is ~40 % of a writer bound export and does not
			// have to happen on the writing thread. Set before the workers start
			tileCodec = precompressionCodec(writer);
			totalTiles = 0;

			// Count total number of tiles
			for (int r = 0; r < nResolutionLevels; r++) {
				totalTiles += (long) resToNX.get(r) * resToNY.get(r);
			}
			totalTiles *= (long) sizeT * sizeC * sizeZ;

			if (writerTask != null) writerTask.setProgressMaximum(totalTiles);

			for (int i = 0; i < nThreads; i++) { // If nThreads = 0: skipped, everything happens in a single thread
				logger.debug(file.getName()+" Export: Starting " + nThreads + " threads.");
				new Thread(() -> {
					try {
						while (computeNextTile()) {
						} // keeps going until no tile needs computation anymore (finished or canceled)
					} catch (Exception e) {
						e.printStackTrace();
					}
					logger.debug(file.getAbsolutePath() + "\t Thread " + Thread.currentThread() + " stopped.");
				}).start();
			}

			for (int r = 0; r < nResolutionLevels; r++) {

				int maxX = mapResToWidth.get(r);
				int maxY = mapResToHeight.get(r);
				int tileX = resToTileX.get(r);
				int tileY = resToTileY.get(r);
				int nXTiles = resToNX.get(r);
				int nYTiles = resToNY.get(r);
				// Bio-formats writers disable tiling when the tile size is 0, and
				// reject negative tile sizes
				int writerTileX = tiled ? tileX : 0;
				int writerTileY = tiled ? tileY : 0;

				if (r < nResolutionLevels - 1) { // No need to write the last one: it won't be used for averaging computation
					// Setup current level writer
					currentLevelWriter = new OMETiffWriter();
					currentLevelWriter.setWriteSequentially(true); // Setting this to false
					// can be problematic!
					currentLevelOmeMeta.setPixelsSizeX(new PositiveInteger(maxX), dstSeries);
					currentLevelOmeMeta.setPixelsSizeY(new PositiveInteger(maxY), dstSeries);

					Unit<Length> unitX = UNITS.REFERENCEFRAME;
					double pixPhysicalSizeX = 1;

					if (omeMeta.getPixelsPhysicalSizeX(oriMetaDataSeries)!=null) {
						if (omeMeta.getPixelsPhysicalSizeX(oriMetaDataSeries).value()!=null) {
							pixPhysicalSizeX = omeMeta.getPixelsPhysicalSizeX(oriMetaDataSeries).value().doubleValue();
						} else {
							logger.warn("UNSPECIFIED PIXEL SIZE IN X, please set it (override pixel size)");
						}
						if (omeMeta.getPixelsPhysicalSizeX(oriMetaDataSeries).unit()!= null) {
							unitX = omeMeta.getPixelsPhysicalSizeX(oriMetaDataSeries).unit();
						} else {
							logger.warn("UNSPECIFIED PIXEL UNIT IN X, please set it (override pixel size)");
						}
					} else {
						logger.warn("UNSPECIFIED PIXEL SIZE IN X, please set it (override pixel size)");
					}

					currentLevelOmeMeta.setPixelsPhysicalSizeX(
							new Length(pixPhysicalSizeX * Math.pow(downsample, r + 1), unitX), dstSeries);

					Unit<Length> unitY = UNITS.REFERENCEFRAME;
					double pixPhysicalSizeY = 1;

					if (omeMeta.getPixelsPhysicalSizeY(oriMetaDataSeries)!=null) {
						if (omeMeta.getPixelsPhysicalSizeY(oriMetaDataSeries).value()!=null) {
							pixPhysicalSizeY = omeMeta.getPixelsPhysicalSizeY(oriMetaDataSeries).value().doubleValue();
						} else {
							logger.warn("UNSPECIFIED PIXEL SIZE IN Y, please set it (override pixel size)");
						}
						if (omeMeta.getPixelsPhysicalSizeY(oriMetaDataSeries).unit()!= null) {
							unitY = omeMeta.getPixelsPhysicalSizeY(oriMetaDataSeries).unit();
						} else {
							logger.warn("UNSPECIFIED PIXEL UNIT IN Y, please set it (override pixel size)");
						}
					} else {
						logger.warn("UNSPECIFIED PIXEL SIZE IN Y, please set it (override pixel size)");
					}

					currentLevelOmeMeta.setPixelsPhysicalSizeY(
							new Length(pixPhysicalSizeY * Math.pow(downsample, r + 1), unitY), dstSeries);

					currentLevelOmeMeta.setPixelsDimensionOrder(DimensionOrder.XYCZT, 0);
					currentLevelWriter.setMetadataRetrieve(currentLevelOmeMeta);
					currentLevelWriter.setBigTiff(true);
					currentLevelWriter.setId(getFileName(r));
					currentLevelWriter.setSeries(dstSeries);
					if (tileCodec != null && precompressible(r)) {
						// This level's tiles reach the temporary writer already compressed
						// for the final file, so the temporary file has to declare the same
						// compression. It costs nothing: the tile is compressed once and
						// written to both files
						currentLevelWriter.setCompression(compression);
					}
					currentLevelWriter.setTileSizeX(writerTileX);
					currentLevelWriter.setTileSizeY(writerTileY);
                    // !!!! weird. See TestOMETIFFRGBMultiScaleTile
                    currentLevelWriter.setInterleaved(r == 0);
				}

				if (r > 0) writer.setInterleaved(false); // But why the heck ???
				logger.debug("Saving resolution size " + r);
				writer.setResolution(r);
				// The tile size can differ between resolution levels: it is reduced
				// when a resolution level is smaller than the requested tile size
				writer.setTileSizeX(writerTileX);
				writer.setTileSizeY(writerTileY);

				currentLevelWritten = r;

				synchronized (tileLock) { // Notifies that a new resolution level is being written: warns other threads to wake up
					tileLock.notifyAll();
				}

				loops: // Tag for cancellation
				for (int t = 0; t < sizeT; t++) {
					for (int c = 0; c < sizeC; c++) {
						for (int z = 0; z < sizeZ; z++) {
							int plane = t * sizeZ * sizeC + c * sizeZ + z;
							// Transfers planes metadata
							if (r == 0) {
								int oriC = range.getRangeC().get(c);
								int oriZ = range.getRangeZ().get(z);
								int oriT = range.getRangeT().get(t);
								int oriPlane = getOriginalPlaneIndex(oriC, oriZ, oriT);
								omeMeta.setPlaneTheC(new NonNegativeInteger(c), dstSeries, plane);
								omeMeta.setPlaneTheZ(new NonNegativeInteger(z), dstSeries, plane);
								omeMeta.setPlaneTheT(new NonNegativeInteger(t), dstSeries, plane);
								KheopsHelper.transferPlaneMeta(oriMetadata, oriMetaDataSeries, oriPlane, omeMeta, dstSeries, plane);
							}
							for (int y = 0; y < nYTiles; y++) {
								for (int x = 0; x < nXTiles; x++) {
									long startX = x * tileX;
									long startY = y * tileY;
									long endX = (x + 1) * (tileX);
									long endY = (y + 1) * (tileY);
									if (endX > maxX) endX = maxX;
									if (endY > maxY) endY = maxY;
									TileIterator.IntsKey key = new TileIterator.IntsKey(new int[]{
											r, t, c, z, y, x});
									if (nThreads == 0) {
										computeTile(key);
										if (isCanceled) {
											break loops;
										}
									} else {
										while (!computedBlocks.containsKey(key)) {
											synchronized (tileLock) {
												tileLock.wait();
											}
											if (isCanceled) {
												break loops;
											}
										}
									}

									byte[] tile = computedBlocks.get(key);
									byte[] compressed = compressedBlocks.remove(key);
									int tileStartX = (int) startX;
									int tileStartY = (int) startY;
									int tileWidth = (int) (endX - startX);
									int tileHeight = (int) (endY - startY);

									if (r < nResolutionLevels - 1) {
										// Hands the tile to the temporary writer and moves on: it is
										// written while the final writer writes the same tile. The
										// arrays stay alive through the lambda, so removing the tile
										// from computedBlocks below is safe
										final OMETiffWriter levelWriter = currentLevelWriter;
										final int tilePlane = plane;
										if (compressed != null) {
											// The very same bytes the final file gets: the tile is
											// serialized once and written to two files
											tempTileWriter.submit(() -> levelWriter.saveCompressedBytes(
													tilePlane, compressed, tileStartX, tileStartY,
													tileWidth, tileHeight));
										}
										else {
											tempTileWriter.submit(() -> levelWriter.saveBytes(tilePlane,
													tile, tileStartX, tileStartY, tileWidth, tileHeight));
										}
									}

									if (compressed != null) {
										writer.saveCompressedBytes(plane, compressed, tileStartX,
												tileStartY, tileWidth, tileHeight);
									}
									else {
										writer.saveBytes(plane, tile, tileStartX, tileStartY,
												tileWidth, tileHeight);
									}

									computedBlocks.remove(key);
									tileIterator.decrementQueue();
									if (writerTask != null) writerTask.setProgressValue(writtenTiles
											.incrementAndGet());
								}
							}
						}
					}
				}
				if (r < nResolutionLevels - 1) {
					// The next level reads this file back, so every tile has to be in
					// it before it is closed
					tempTileWriter.awaitDrain();
					currentLevelWriter.close();
				}
			}
			// Proper clean-up and handling of cancelation
			if (nThreads == 0) { // Serial mode : the local writer needs to be closed
				if (writerTask != null) {
					writerTask.setStatusMessage("Closing readers.");
				}
				if (localReader.get() != null) {
					localReader.get().close();
					logger.debug("Local reader of " + file.getName() + " closed.");
				}
			}
			// Delete temp files (= single resolution files)
			if (writerTask != null) {
				writerTask.setStatusMessage("Deleting temporary files.");
			}
			for (int r = 0; r < nResolutionLevels - 1; r++) {
				boolean result = new File(getFileName(r)).delete();
				if (!result) logger.warn("File " + getFileName(r) + " couldn't be deleted.");
			}
			computedBlocks.clear();
			compressedBlocks.clear();
			// Closing the file can take a huge amount of time - the planned time can be displayed
			// if a task monitor has been given
			if (writerTask != null) {
				// Let's do a quick computation based on the following assumption: 5
				// minutes for 100k blocks
				int estimateTimeMin = (int) (5 * totalTiles / 1e5);
				if (estimateTimeMin < 2) {
					writerTask.setStatusMessage(
							"Closing writer... please wait a few minutes.");
				} else {
					writerTask.setStatusMessage(
							"Closing writer... please wait, this can take around " +
									estimateTimeMin + " minutes.");
				}
			}
			try {
				writer.close();
				logger.debug("Writer of " + file.getName() + " closed.");
			} catch (Exception e) {
				if (isCanceled) {
					logger.error("Error during cancellation: " + e.getMessage());
				} else {
					e.printStackTrace();
				}
			} finally {
				if (isCanceled) {
					boolean result = new File(file.getAbsolutePath()).delete();
					if (!result) {
						logger.warn("Cancellation: could not delete file " + file.getAbsolutePath());
					}
				}
			}
		} finally {
			if (tempTileWriter != null) tempTileWriter.close();
			if (writerTask != null) writerTask.finish();
		}
		}
	}

	private int getOriginalPlaneIndex(int oriC, int oriZ, int oriT) {
		switch (oriMetadata.getPixelsDimensionOrder(oriMetaDataSeries)) {
			case XYZCT: return oriT * sizeC * sizeZ + oriC * sizeZ + oriZ;
			case XYZTC: return oriC * sizeT * sizeZ + oriT * sizeZ + oriZ;
			case XYCTZ: return oriZ * sizeT * sizeC + oriT * sizeC + oriC;
			case XYCZT: return oriT * sizeZ * sizeC + oriZ * sizeC + oriC;
			case XYTCZ: return oriZ * sizeC * sizeT + oriC * sizeT + oriT;
			case XYTZC: return oriC * sizeZ * sizeT + oriZ * sizeT + oriT;
			default: throw new IllegalArgumentException("Unknown dimension order "+oriMetadata.getPixelsDimensionOrder(oriMetaDataSeries));
		}
	}

	private String getFileName(int r) {
		return FilenameUtils.removeExtension(file.getAbsolutePath()) + "_lvl_" + r +
				".ome.tiff";
	}

	/**
	 * Writes tiles to the temporary single resolution file on a thread of its
	 * own, so that the write overlaps with the write of the same tile to the
	 * final pyramidal file.
	 * <p>
	 * Every tile of a resolution level below the last one is written twice: once
	 * to the temporary file the next level is downsampled from, and once to the
	 * final file. Both writes used to run one after the other on the single
	 * thread which drives the export, and the temporary one was measured at 19 to
	 * 25 % of the export time, see
	 * <a href="https://github.com/BIOP/ijp-kheops/issues/12">issue #12</a>. They
	 * go to two different files, so nothing prevents them from running at the
	 * same time.
	 * <p>
	 * The queue is deliberately short. The temporary write is about three times
	 * cheaper than the final one, so the thread only ever has to stay one tile
	 * ahead, and every queued tile is one more tile array kept alive on top of
	 * the {@code maxTilesInQueue} the exporter already allows.
	 */
	private static class AsyncTileWriter implements AutoCloseable {

		/** A write to perform on the writing thread */
		interface Write {

			void run() throws Exception;
		}

		/** Makes the thread return; never run */
		private static final Write END = () -> {};

		private final ArrayBlockingQueue<Write> queue;
		private final Thread thread;
		/** The first failure, rethrown to the thread driving the export */
		private final AtomicReference<Exception> failure = new AtomicReference<>();

		AsyncTileWriter(String name, int queueDepth) {
			queue = new ArrayBlockingQueue<>(queueDepth);
			thread = new Thread(this::writeUntilDone, "Kheops temp writer " + name);
			thread.setDaemon(true);
			thread.start();
		}

		private void writeUntilDone() {
			while (true) {
				Write write;
				try {
					write = queue.take();
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				if (write == END) return;
				try {
					write.run();
				}
				catch (Exception e) {
					// Only the first one is of interest: the writes which follow it
					// fail because the writer is already broken. The export stops at
					// the next submit or drain anyway
					failure.compareAndSet(null, e);
				}
			}
		}

		/** Blocks while the queue is full, which is what bounds the memory */
		void submit(Write write) throws Exception {
			throwIfFailed();
			try {
				queue.put(write);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw e;
			}
		}

		/**
		 * Returns once everything submitted so far has been written. Has to be
		 * called before the temporary writer is closed, and before the next
		 * resolution level reads the file back.
		 */
		void awaitDrain() throws Exception {
			CountDownLatch written = new CountDownLatch(1);
			try {
				queue.put(written::countDown);
				written.await();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw e;
			}
			throwIfFailed();
		}

		private void throwIfFailed() throws Exception {
			Exception failed = failure.get();
			if (failed != null) throw failed;
		}

		/**
		 * Stops the thread. Pending tiles are dropped rather than written, which
		 * only ever happens on cancellation: the normal path drains at the end of
		 * every resolution level. A write already in flight is left to finish, so
		 * that no file is closed under it.
		 */
		@Override
		public void close() {
			while (!queue.offer(END))
				queue.poll();
			try {
				thread.join();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	public static OMETiffExporterBuilder.Data.DataBuilder builder() {
		return OMETiffExporterBuilder.defineData();
	}

	/**
	 * Entry point for creating a OMETiff exporter object
	 * Define, in this order, using this builder:
	 * - data
	 * - metadata
	 * - write options
	 * <p>
	 * The most simple example looks like:
	 *             OMETiffExporter.builder()
	 *                     .defineData()
	 *                     .putXYZRAI(img)
	 *                     .defineMetaData("Image")
	 *                     .defineWriteOptions()
	 *                     .savePath(path)
	 *                     .create().export();
	 * <p>
	 *  For more advanced examples, see KheopsCommand
	 *
	 */
	public static class OMETiffExporterBuilder {

		public static Data.DataBuilder defineData() {
			return new Data.DataBuilder();
		}
		public static class Data<T> {

			protected final int pixelsSizeX, pixelsSizeY, pixelsSizeZ, pixelsSizeC, pixelsSizeT;
			protected final Map<Integer, Map<Integer, RandomAccessibleInterval<T>>> ctToRAI;

			protected final T pixelInstance;

			private Data(DataBuilder<T> builder) {
				this.pixelsSizeX = builder.nPixelX;
				this.pixelsSizeY = builder.nPixelY;
				this.pixelsSizeZ = builder.nPixelZ;
				this.pixelsSizeC = builder.nChannels;
				this.pixelsSizeT = builder.nTimePoints;
				this.ctToRAI = builder.ctToRAI;
				this.pixelInstance = builder.pixelInstance;
			}

			/**
			 * Builder for raw pixel data - single 5D image XYZCT supported.
			 * See {@link SourceToByteArray#validPixelType(Object)} to see which pixel types
			 * are supported.
			 * @param <T> pixel type
			 */
			public static class DataBuilder<T> {
				private int nPixelX = -1, nPixelY = -1, nPixelZ = -1;
				private int nChannels = -1, nTimePoints = -1;
				final private Map<Integer, Map<Integer, RandomAccessibleInterval<T>>> ctToRAI = new HashMap<>();
				T pixelInstance;

				/**
				 * Adds a {@link Source} in the data. A Source contains a single channel and all
				 * timepoints. Timepoints are iterated until one is found missing.
				 * @param channel defines which channel of the exported tiff this source will occupy
				 * @param source bdv source structure
				 * @return data builder
				 * @throws UnsupportedOperationException for instance, if the pixel type is not supported, or if the channel is already defined
				 */
				public DataBuilder<T> put(int channel, Source<T> source) throws UnsupportedOperationException {
					int t = 0;
					while (source.isPresent(t)) {
						putXYZRAI(channel, t, source.getSource(t,0));
						t++;
					}
					return this;
				}

				/**
				 * Adds a {@link Source} in the data. A Source contains a single channel and all
				 * timepoints. Timepoints are iterated until one is found missing.
				 * This source is put on channel 0.
				 * @param source bdv source structure
				 * @return data builder
				 * @throws UnsupportedOperationException for instance, if the pixel type is not supported, or if the channel 0 already defined
				 */
				public DataBuilder<T> put(Source<T> source) throws UnsupportedOperationException {
					return put(0, source);
				}

				/**
				 * Adds an array of {@link Source} in the data. A single Source defines a single channel and all
				 * timepoints. Timepoints are iterated until one is found missing. The index of each source corresponds
				 * to its channel.
				 * @param sources sources
				 * @return data builder
				 * @throws UnsupportedOperationException for instance, if the pixel type is not supported, or if there is some data overlap
				 */
				public DataBuilder<T> put(SourceAndConverter<T>[] sources) throws UnsupportedOperationException {
					for (int c = 0; c<sources.length;c++) {
						put(c, sources[c]);
					}
					return this;
				}

				/**
				 * Gets the {@link Source} from the {@link SourceAndConverter} then
				 * calls {@link DataBuilder#put(int, Source)}
				 * @param c channel index to place the source
				 * @param source source and converter
				 * @return data builder
                 */
				public DataBuilder<T> put(int c, SourceAndConverter<T> source) throws UnsupportedOperationException {
					put(c, source.getSpimSource());
					return this;
				}

				/**
				 * Gets the {@link Source} from the {@link SourceAndConverter} then
				 * calls {@link DataBuilder#put(int, Source)}
				 * @param source source and converter
				 * @return data builder
				 * @throws UnsupportedOperationException
				 */
				public DataBuilder<T> put(SourceAndConverter<T> source) throws UnsupportedOperationException {
					put(0, source.getSpimSource());
					return this;
				}

				/**
				 * Puts a 2D or 3D {@link RandomAccessibleInterval} at the exported channel and timepoint defined in the argument.
				 * In case a 2D rai is put, it is assumed to be a single plane, and a third dimension of size 1
				 * is added to the RAI. Throws UnsupportedOperationException if the pixel type is unsupported, if the data is already defined, etc.
				 * @param channel index of the channel in the exported image (0-based)
				 * @param timepoint index of the timepoint in the exported image (0-based)
				 * @param rai 2D or 3D image
				 * @return data builder
				 * @throws UnsupportedOperationException
				 */
				public DataBuilder<T> putXYZRAI(int channel, int timepoint, RandomAccessibleInterval<T> rai) throws UnsupportedOperationException {
					if (rai.numDimensions()==2) {
						rai = Views.addDimension(rai,0,0);
					}
					validate(channel, timepoint);
					validate(rai);
					ctToRAI.get(channel).put(timepoint, rai);
					return this;
				}

				/**
				 * As {@link DataBuilder#putXYZRAI(int, int, RandomAccessibleInterval)}, implying
				 * channel = 0 and timepoint = 0
				 * @param rai 2D or 3D image
				 * @return data builder
				 * @throws UnsupportedOperationException
				 */
				public DataBuilder<T> putXYZRAI(RandomAccessibleInterval<T> rai) throws UnsupportedOperationException {
					putXYZRAI(0,0,rai);
					return this;
				}

				/**
				 * To be called to start the definition of the associated metadata
				 * @param imageName a name for the image, compulsory
				 * @return metadata builder
				 */
				public MetaData.MetaDataBuilder defineMetaData(String imageName) { // next step
					if (nChannels<1) {
						throw new UnsupportedOperationException("No channel found, nChannels = "+nChannels+". You probably did not specify any data.");
					}
					if (nTimePoints<1) {
						throw new UnsupportedOperationException("No timepoint found, nTimepoints = "+nTimePoints+". You probably did not specify any data.");
					}
					// Check if no data is missing
					for (int c = 0; c<nChannels; c++) {
						for (int t = 0; t<nTimePoints; t++) {
							if (!ctToRAI.containsKey(c)) throw new UnsupportedOperationException("Channel "+c+" missing. You probably forgot to specify the data for this channel.");
							if (!ctToRAI.get(c).containsKey(t)) throw new UnsupportedOperationException("Timepoint "+t+" missing for channel "+c+". You probably forgot to specify the data for this channel and timepoint.");
						}
					}
					Data data = new Data(this);
					return new MetaData.MetaDataBuilder(data, imageName);
				}

				private void validate(int channel, int timepoint) throws UnsupportedOperationException {
					if (channel<0) throw new UnsupportedOperationException("Channel index can't be negative");
					if (timepoint<0) throw new UnsupportedOperationException("Timepoint index can't be negative");
					if (channel+1>nChannels) nChannels = channel+1;
					if (timepoint+1>nTimePoints) nTimePoints = timepoint+1;
					if (!ctToRAI.containsKey(channel)) {
						ctToRAI.put(channel, new HashMap<>());
					}
					if (ctToRAI.get(channel).containsKey(timepoint)) {
						throw new UnsupportedOperationException("You can't specify two times the same channel and timepoint");
					}
				}

				private void validate(RandomAccessibleInterval<T> rai) throws UnsupportedOperationException {
					if (rai.numDimensions()!=3) throw new UnsupportedOperationException("All random accessible intervals should be 3D");
					if (nPixelX == -1) { // First RAI given, let's take the sizes
						if (rai.dimension(0)>Integer.MAX_VALUE) throw new UnsupportedOperationException("Image too big along X ("+rai.dimension(0)+">"+Integer.MAX_VALUE+")");
						if (rai.dimension(1)>Integer.MAX_VALUE) throw new UnsupportedOperationException("Image too big along Y ("+rai.dimension(1)+">"+Integer.MAX_VALUE+")");
						if (rai.dimension(2)>Integer.MAX_VALUE) throw new UnsupportedOperationException("Image too big along Z ("+rai.dimension(2)+">"+Integer.MAX_VALUE+")");
						nPixelX = (int) rai.dimension(0);
						nPixelY = (int) rai.dimension(1);
						nPixelZ = (int) rai.dimension(2);
						pixelInstance = rai.getAt(0,0,0);
						if (!validPixelType(pixelInstance)) {
							throw new UnsupportedOperationException("Unhandled pixel type class: " +
									pixelInstance.getClass().getName());
						}
					}
					if (rai.dimension(0)!=nPixelX) throw new UnsupportedOperationException("All random accessible intervals should have the same dimension (size X: "+nPixelX+" != "+rai.dimension(0));
					if (rai.dimension(1)!=nPixelY) throw new UnsupportedOperationException("All random accessible intervals should have the same dimension (size Y: "+nPixelY+" != "+rai.dimension(1));
					if (rai.dimension(2)!=nPixelZ) throw new UnsupportedOperationException("All random accessible intervals should have the same dimension (size Z: "+nPixelZ+" != "+rai.dimension(2));
					// Can't test the type... huge penalty if calling get(0,0,0) because it can trigger the loading of the data (I think)
				}
			}
		}

		/**
		 * Builder that defines the metadata of defined raw Data.
		 * Internally, a {@link IMetadata} object is used to store metadata.
		 */
		public static class MetaData {
			public final int series = 0;
			public final IMetadata omeMeta;
			private MetaData(MetaData.MetaDataBuilder builder) {
				this.omeMeta = builder.omeMeta;
			}
			public static class MetaDataBuilder {
				final int series = 0;
				IMetadata omeMeta;
				final Data data;
				final boolean isRGB;
				public MetaDataBuilder(Data data, String imageName) {
					this.data = data;
					omeMeta = MetadataTools.createOMEXMLMetadata();
					final String pixelType;
					final int samplePerPixel;
					final String dimensionOrder;

					if (data.pixelInstance instanceof UnsignedShortType) {
						pixelType = PixelType.UINT16.toString();
						samplePerPixel = 1;
						isRGB = false;
						dimensionOrder = DimensionOrder.XYZCT.getValue();
					}
					else if (data.pixelInstance instanceof UnsignedByteType) {
						pixelType = PixelType.UINT8.toString();
						samplePerPixel = 1;
						isRGB = false;
						dimensionOrder = DimensionOrder.XYZCT.getValue();
					}
					else if (data.pixelInstance instanceof FloatType) {
						pixelType = PixelType.FLOAT.toString();
						samplePerPixel = 1;
						isRGB = false;
						dimensionOrder = DimensionOrder.XYZCT.getValue();
					}
					else if (data.pixelInstance instanceof ARGBType) {
						pixelType = PixelType.UINT8.toString();
						samplePerPixel = 3;
						isRGB = true;
						dimensionOrder = DimensionOrder.XYCZT.getValue();
					} else {
						throw new UnsupportedOperationException("Unhandled pixel type class: " +
								data.pixelInstance.getClass().getName());
					}

					MetadataTools
							.populateMetadata(
									omeMeta,
									series,
									imageName,
									true,
									dimensionOrder,
									pixelType,
									data.pixelsSizeX,
									data.pixelsSizeY,
									data.pixelsSizeZ,
									isRGB ? data.pixelsSizeC * 3 : data.pixelsSizeC,
									data.pixelsSizeT,
									samplePerPixel);

					// Set default values
					if (isRGB) {
						omeMeta.setChannelID("Channel:0", series, 0);
						omeMeta.setChannelName("Channel_0", series, 0);
						omeMeta.setPixelsInterleaved(true, series);
					} else {
						omeMeta.setPixelsInterleaved(false, series);
						for (int c = 0; c < data.pixelsSizeC; c++) {
							omeMeta.setChannelID("Channel:0:" + c, series, c);
							omeMeta.setChannelName("Channel_" + c, series, c);
							omeMeta.setChannelSamplesPerPixel(new PositiveInteger(1), series, c);
							omeMeta.setChannelColor(new Color(255, 255, 255,255), series, c);
						}
					}

					omeMeta.setPixelsPhysicalSizeX(new Length(1, UNITS.REFERENCEFRAME), series);
					omeMeta.setPixelsPhysicalSizeY(new Length(1, UNITS.REFERENCEFRAME), series);
					omeMeta.setPixelsPhysicalSizeZ(new Length(1, UNITS.REFERENCEFRAME), series);
				}

				/**
				 * To overidde the image name
				 * @param imageName new image name
				 * @return metadata builder
				 */
				public MetaDataBuilder imageName(String imageName) {
					omeMeta.setImageName(imageName, series);
					return this;
				}

				/**
				 * Give a name to a channel
				 * @param channel channel index (0-based)
				 * @param channelName channel name
				 * @return metadata builder
				 */
				public MetaDataBuilder channelName(int channel, String channelName) {
					omeMeta.setChannelID("Channel:0:" + channel, series, channel);
					omeMeta.setChannelName(channelName, series, channel);
					return this;
				}

				/**
				 * Give a color to a channel (doesn't work with RGB obviously)
				 * @param channel channel index (0-based)
				 * @param r red component (8 bit)
				 * @param g green component (8 bit)
				 * @param b blue component (8 bit)
				 * @param a alpha component (8 bit)
				 * @return metadata builder
				 */
				public MetaDataBuilder channelColor(int channel, int r, int g, int b, int a) {
					omeMeta.setChannelColor(new Color(r, g, b, a), series, channel);
					return this;
				}

				/**
				 * If you need full flexibility, this builder function exposes the internal
				 * IMetadata object used for the metadata builder
				 * @param f function with a IMetadata input and a IMetadata output
				 * @return metadata builder
				 */
				public MetaDataBuilder applyOnMeta(Function<IMetadata, IMetadata> f) {
					omeMeta = f.apply(this.omeMeta);
					return this;
				}

				/**
				 * Defines a plane position.
				 * @param originX origin plane position in X
				 * @param originY origin plane position in Y
				 * @param originZ origin plane position in Z
				 * @param planeIndex in most cases planeIndex = t * sizeZ * sizeC + c * sizeZ + z
				 * @return metadata builder
				 */
				public MetaDataBuilder planePosition(Length originX, Length originY, Length originZ, int planeIndex) {
					omeMeta.setPlanePositionX(originX, series, planeIndex);
					omeMeta.setPlanePositionY(originY, series, planeIndex);
					omeMeta.setPlanePositionZ(originZ, series, planeIndex);
					return this;
				}

				/**
				 * See {@link MetaDataBuilder#planePosition(Length, Length, Length, int)}
				 * @param originX origin plane position in X
				 * @param originY origin plane position in Y
				 * @param originZ origin plane position in Z
				 * @param planeIndex in most cases planeIndex = t * sizeZ * sizeC + c * sizeZ + z
				 * @return metadata builder
				 */
				public MetaDataBuilder planePositionMicrometer(double originX, double originY, double originZ, int planeIndex) {
					return planePosition(new Length(originX, UNITS.MICROMETER),
							new Length(originY, UNITS.MICROMETER),
							new Length(originZ, UNITS.MICROMETER),
							planeIndex
					);
				}

				/**
				 * See {@link MetaDataBuilder#planePosition(Length, Length, Length, int)}
				 * @param originX origin plane position in X
				 * @param originY origin plane position in Y
				 * @param originZ origin plane position in Z
				 * @param planeIndex in most cases planeIndex = t * sizeZ * sizeC + c * sizeZ + z
				 * @return metadata builder
				 */
				public MetaDataBuilder planePositionMillimeter(double originX, double originY, double originZ, int planeIndex) {
					return planePosition(new Length(originX, UNITS.MILLIMETER),
							new Length(originY, UNITS.MILLIMETER),
							new Length(originZ, UNITS.MILLIMETER),
							planeIndex
					);
				}

				public MetaDataBuilder pixelsTimeIncrementInS(double timeInS) {
					omeMeta.setPixelsTimeIncrement(new Time(timeInS, UNITS.SECOND), series);
					return this;
				}

				public MetaDataBuilder voxelPhysicalSize(Length physicalSizeX, Length physicalSizeY, Length physicalSizeZ) {
					omeMeta.setPixelsPhysicalSizeX(physicalSizeX,series);
					omeMeta.setPixelsPhysicalSizeY(physicalSizeY,series);
					omeMeta.setPixelsPhysicalSizeZ(physicalSizeZ, series);
					return this;
				}

				public MetaDataBuilder voxelPhysicalSizeMicrometer(double physicalSizeXInMicrometer, double physicalSizeYInMicrometer, double physicalSizeZInMicrometer) {
					return voxelPhysicalSize(
							new Length(physicalSizeXInMicrometer, UNITS.MICROMETER),
							new Length(physicalSizeYInMicrometer, UNITS.MICROMETER),
							new Length(physicalSizeZInMicrometer, UNITS.MICROMETER));
				}

				public MetaDataBuilder voxelPhysicalSizeMillimeter(double physicalSizeXInMillimeter, double physicalSizeYInMillimeter, double physicalSizeZInMillimeter) {
					return voxelPhysicalSize(
							new Length(physicalSizeXInMillimeter, UNITS.MILLIMETER),
							new Length(physicalSizeYInMillimeter, UNITS.MILLIMETER),
							new Length(physicalSizeZInMillimeter, UNITS.MILLIMETER));
				}

				/**
				 * Go to next step of exporter definition
				 * @return write options builder
				 */
				public WriterOptions.WriterOptionsBuilder defineWriteOptions() {
					return new WriterOptions.WriterOptionsBuilder(new MetaData(this), data);
				}

				public MetaDataBuilder putMetadataFromSources(SourceAndConverter<?> sac, Unit<Length> unit) {
					return this.putMetadataFromSources(new SourceAndConverter<?>[]{sac}, unit);
				}

				public MetaDataBuilder putMetadataFromSources(SourceAndConverter<?>[] sacs, Unit<Length> unit) {
					Source<?>[] sources = new Source[sacs.length];
					for (int i = 0; i< sacs.length; i++) {
						sources[i] = sacs[i].getSpimSource();
					}
					putMetadataFromSources(sources, unit);

					// Now, let's put the colors!
					int sizeC = sources.length;
					for (int c = 0; c < sizeC; c++) {
						if (sacs[c].getConverter() instanceof ColorConverter) {
							ColorConverter converter = (ColorConverter) sacs[c].getConverter();
							// Converters of RGB sources (ScaledARGBConverter) do not carry a color:
							// they report supportsColor() == false and getColor() == 0. Writing that 0
							// would set the OME Channel Color to a fully transparent black, which readers
							// turn into an all black LUT over correct pixels.
							if (!converter.supportsColor()) continue;
							int colorCode = converter.getColor().get();
							int colorRed = ARGBType.red(colorCode);
							int colorGreen = ARGBType.green(colorCode);
							int colorBlue = ARGBType.blue(colorCode);
							int colorAlpha = ARGBType.alpha(colorCode);
							this.channelColor(c,colorRed, colorGreen, colorBlue, colorAlpha);
						}
					}


					return this;
				}

				public MetaDataBuilder putMetadataFromSources(Source<?>[] sources, Unit<Length> unit) {

					// Voxel size
					AffineTransform3D mat = new AffineTransform3D();
					Source<?> model = sources[0];
					model.getSourceTransform(0, 0, mat);

					double[] m = mat.getRowPackedCopy();

					final double[] voxelSizes = new double[3];

					for (int d = 0; d < 3; ++d) {
						voxelSizes[d] = Math.sqrt(m[d] * m[d] + m[d + 4] * m[d + 4] + m[d + 8] *
								m[d + 8]);
					}

					this.voxelPhysicalSize(new Length(voxelSizes[0], unit), new Length(voxelSizes[1], unit),new Length(voxelSizes[2], unit));

					final RealPoint origin = new RealPoint(3);
					//	Plane position
					mat.apply(origin, origin);

					this.planePosition(new Length(origin.getDoublePosition(0), unit),
							new Length(origin.getDoublePosition(1), unit),
							new Length(origin.getDoublePosition(2), unit),0
							);

					return this;
				}

			}
		}
		public static class WriterOptions {

			final public int nThreads;
			final public String rangeC;
			final public String rangeZ;
			final public String rangeT;
			final public String path;
			final public int tileX;
			final public int tileY;
			final public String compression;
			final public int maxTilesInQueue;
			final public TaskService taskService;
			final public int nResolutions;
			final public int downSample;

			private WriterOptions(WriterOptionsBuilder builder) {
				this.nThreads = builder.nThreads;
				this.rangeC = builder.rangeC;
				this.rangeZ = builder.rangeZ;
				this.rangeT = builder.rangeT;
				this.path = builder.filePath;
				this.tileX = builder.tileX;
				this.tileY = builder.tileY;
				this.compression = builder.compression;
				this.maxTilesInQueue = builder.maxTilesInQueue;
				this.taskService = builder.taskService;
				this.nResolutions = builder.nResolutions;
				this.downSample = builder.downSample;
			}

			public static class WriterOptionsBuilder {
				final Data data;
				final MetaData metaData;
				int nThreads = Runtime.getRuntime().availableProcessors();
				String rangeC = "";
				String rangeZ = "";
				String rangeT = "";
				String filePath = "";
				int tileX = 512;
				int tileY = 512;
				// Default compression - uncompressed() has to be called explicitly if no
				// compression is wanted
				String compression = CompressionType.LZW.getCompression();
				int maxTilesInQueue = 60;
				TaskService taskService = null;
				int nResolutions = 1;
				int downSample = 2;
				public WriterOptionsBuilder(MetaData metaData, Data data) {
					this.data = data;
					this.metaData = metaData;
				}

				public WriterOptionsBuilder nThreads(int nThreads) {
					this.nThreads = nThreads;
					return this;
				}

				public WriterOptionsBuilder rangeC(String rangeC) {
					this.rangeC = rangeC;
					return this;
				}

				public WriterOptionsBuilder rangeZ(String rangeZ) {
					this.rangeZ = rangeZ;
					return this;
				}

				public WriterOptionsBuilder rangeT(String rangeT) {
					this.rangeT = rangeT;
					return this;
				}

				public WriterOptionsBuilder tileSize(int tileX, int tileY) {
					this.tileX = tileX;
					this.tileY = tileY;
					return this;
				}

				public WriterOptionsBuilder downsample(int downsample) {
					this.downSample = downsample;
					return this;
				}

				public WriterOptionsBuilder nResolutionLevels(int nResolutions) {
					this.nResolutions = nResolutions;
					return this;
				}

				public WriterOptionsBuilder lzw() {
					this.compression = CompressionType.LZW.getCompression();
					return this;
				}

				/**
				 * Writes the image without any compression. Since the default compression
				 * of this builder is LZW, this method has to be called explicitly in order
				 * to get an uncompressed ome tiff file.
				 *
				 * @return the builder
				 */
				public WriterOptionsBuilder uncompressed() {
					this.compression = CompressionType.UNCOMPRESSED.getCompression();
					return this;
				}

				/**
				 * see CompressionTypes
				 *
				 * @return the builder
				 */
				public WriterOptionsBuilder j2k() {
					this.compression = CompressionType.J2K.getCompression();
					return this;
				}

				/**
				 * see CompressionTypes
				 *
				 * @return the builder
				 */
				public WriterOptionsBuilder j2kLossy() {
					this.compression = CompressionType.J2K_LOSSY.getCompression();
					return this;
				}

				/**
				 * see CompressionTypes
				 *
				 * @return write options builder
				 */
				public WriterOptionsBuilder jpg() {
					this.compression = CompressionType.JPEG.getCompression();
					return this;
				}

				/**
				 * If a taskService is provided, the export timing can be monitored or even canceled
				 * @param taskService a service that creates task
				 * @return write options builder
				 */
				public WriterOptionsBuilder monitor(TaskService taskService) {
					this.taskService = taskService;
					return this;
				}

				/**
				 * If the export is multithreaded, each thread will try to compute as many tiles
				 * as possible in advance, but the number of computed tile in advance will never
				 * exceed this value
				 * @param max maximum number of tiles computed in advance
				 * @return write options builder
				 */
				public WriterOptionsBuilder maxTilesInQueue(int max) {
					this.maxTilesInQueue = max;
					return this;
				}

				public WriterOptionsBuilder compression(String compression) {
					this.compression = compression;
					return this;
				}

				public WriterOptionsBuilder compression(int code) {
					this.compression = CompressionType.get(code).getCompression();
					return this;
				}

				/**
				 * Where to save the exported ome tiff - the path should be valid and not point towards an existing file
				 * @param path file absolute path, with .ome.tiff extension
				 * @return builder
				 */
				public WriterOptionsBuilder savePath(String path) {
					this.filePath = path;
					return this;
				}

				public OMETiffExporter create() throws Exception {
					if ((filePath == null)||(filePath.trim().isEmpty())) {
						throw new IOException("Invalid path file");
					}

					if (new File(filePath).exists()) {
						throw new IOException("Path "+filePath+" already exists");
					}

					if (filePath.endsWith(".ome.tiff")||filePath.endsWith(".ome.tif")) {
						// OK
					} else {
						System.out.println("Warning: You try to export an ome tiff file but did not specify an '.ome.tiff' extension.");
					}

					WriterOptions wOpts = new WriterOptions(this);
					return new OMETiffExporter(data.ctToRAI, metaData.omeMeta, metaData.series, wOpts);
				}
			}
		}
	}

}
