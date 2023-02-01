/*-
 * #%L
 * IJ2 commands that use bio-formats to create pyramidal ome.tiff
 * %%
 * Copyright (C) 2018 - 2022 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP)
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
import ch.epfl.biop.bdv.img.ResourcePool;
import ch.epfl.biop.kheops.CZTRange;
import ch.epfl.biop.kheops.KheopsHelper;
import loci.common.image.IImageScaler;
import loci.formats.IFormatReader;
import loci.formats.ImageReader;
import loci.formats.MetadataTools;
import loci.formats.in.OMETiffReader;
import loci.formats.meta.IMetadata;
import loci.formats.meta.IPyramidStore;
import loci.formats.out.OMETiffWriter;
import loci.formats.out.PyramidOMETiffWriter;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.Type;
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
import ome.xml.meta.MetadataConverter;
import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.enums.PixelType;
import ome.xml.model.primitives.Color;
import ome.xml.model.primitives.PositiveInteger;
import org.apache.commons.io.FilenameUtils;
import org.scijava.task.Task;
import org.scijava.task.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Exports a structure of {@link RandomAccessibleInterval<T>} into a OME-TIFF file,
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
// TODO : Scale Z pixel size
// Perf potential improvement : dual writing current res level and final

public class OMETiffExporter<T extends NumericType<T>> {

	private static final Logger logger = LoggerFactory.getLogger(
		OMETiffExporter.class);

	// ------------ Data and metadata
	final Map<Integer, Map<Integer, RandomAccessibleInterval<T>>> ctToRAI;
	final IMetadata oriMetadata;
	final int oriMetaDataSeries;

	final ResourcePool<IFormatReader> readerPool;
	final int readerPoolSeries;

	// ------------ Saving options
	final CZTRange range; // To save a subset of C Z or T
	final long tileX, tileY;
	final int nResolutionLevels;
	final int downsample;
	final String compression;
	final boolean compressTempFile;
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

	final boolean isLittleEndian;
	final boolean isRGB;
	final boolean isInterleaved;
	final boolean isFloat;
	final int bytesPerPixel;

	// ------------ Fields updated live during the saving
	final AtomicLong writtenTiles = new AtomicLong();
	final Map<TileIterator.IntsKey, byte[]> computedBlocks;
	final TileIterator tileIterator;
	final Task writerTask;
	final Object tileLock = new Object();
	final ThreadLocal<OMETiffReader> localReader = new ThreadLocal<>(); // One reader per thread
	final ThreadLocal<IImageScaler> localScaler = new ThreadLocal<>(); // One scaler per thread
	final ThreadLocal<Integer> localResolution = new ThreadLocal<>(); // Current resolution level of the thread
	volatile int currentLevelWritten = -1;

	final Function<TileIterator.IntsKey, byte[]> lvl0DataFetcher;

	protected OMETiffExporter(
			// Image data and metadata, + czt optional subsetting
			Map<Integer, Map<Integer, RandomAccessibleInterval<T>>> ctToRAI, // Image data
			IMetadata originalOmeMeta, int originalSeries,
			// Writing options
			OMETiffExporterBuilder.WriterOptions writerSettings,
			ResourcePool<IFormatReader> readerPool, int readerPoolSeries) throws Exception {
		// Monitoring
		if (writerSettings.taskService != null) {
			this.writerTask = writerSettings.taskService.createTask("Writing: " + new File(writerSettings.path).getName());
		}
		else {
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

		width = originalOmeMeta.getPixelsSizeX(originalSeries).getValue();
		height = originalOmeMeta.getPixelsSizeY(originalSeries).getValue(); // Check that it's not wrong with a problem of one

		// For exporting a subset of the original image
		int iniSizeZ = originalOmeMeta.getPixelsSizeZ(originalSeries).getValue();
		int iniSizeT = originalOmeMeta.getPixelsSizeT(originalSeries).getValue();
		int iniSizeC = originalOmeMeta.getPixelsSizeC(originalSeries).getValue();
		range = CZTRange.builder().setC(writerSettings.rangeC).setT(writerSettings.rangeT).setZ(writerSettings.rangeT).get(isRGB ? 1:iniSizeC, iniSizeZ, iniSizeT);
		sizeC = range.getRangeC().size();
		sizeZ = range.getRangeZ().size();
		sizeT = range.getRangeT().size();
		//System.out.println("#C"+sizeC+"#Z"+sizeZ+"#T"+sizeT);

		mapResToWidth.put(0, width);
		mapResToHeight.put(0, height);

		for (int i = 0; i < writerSettings.nResolutions - 1; i++) {
			mapResToWidth.put(i + 1, (int) (width / Math.pow(writerSettings.downSample, i + 1)));
			mapResToHeight.put(i + 1, (int) (height / Math.pow(writerSettings.downSample, i + 1)));
		}

		// Saving options
		this.compressTempFile = writerSettings.compressTempFiles;
		this.downsample = writerSettings.downSample;
		this.nResolutionLevels = writerSettings.nResolutions;
		this.file = new File(writerSettings.path);
		this.compression = writerSettings.compression;
		this.nThreads = writerSettings.nThreads;
		// Tile size should be a multiple of 16
		int tempTileSizeX = writerSettings.tileX;
		int tempTileSizeY = writerSettings.tileY;
		if (width<=writerSettings.tileX) {
			tempTileSizeX = width;
		}
		if (height<=writerSettings.tileY) {
			tempTileSizeX = height;
		}
		this.tileX = tempTileSizeX<16?16:Math.round((float)tempTileSizeX / 16.0F) * 16;
		this.tileY = tempTileSizeY<16?16:Math.round((float)tempTileSizeY / 16.0F) * 16;

		// One iteration to count the number of tiles
		// some assertion : same dimensions for all nr and c and t
		for (int r = 0; r < writerSettings.nResolutions; r++) {
			int nXTiles;
			int nYTiles;
			int maxX, maxY;
			if (r != 0) {
				maxX = mapResToWidth.get(r);
				maxY = mapResToHeight.get(r);
			}
			else {
				maxX = width;
				maxY = height;
			}
			nXTiles = (int) Math.ceil(maxX / (double) writerSettings.tileX);
			nYTiles = (int) Math.ceil(maxY / (double) writerSettings.tileY);
			resToNX.put(r, nXTiles);
			resToNY.put(r, nYTiles);
		}

		// Initialise transient variables for exporting
		writtenTiles.set(0);
		tileIterator = new TileIterator(nResolutionLevels, sizeT, sizeC, sizeZ,
			resToNY, resToNX, writerSettings.maxTilesInQueue);
		computedBlocks = new ConcurrentHashMap<>(nThreads * 3 + 1); // should be enough to avoiding overlap of hash

		if (readerPool == null) {
			this.readerPool = null;
			this.readerPoolSeries = -1;
			lvl0DataFetcher = this::getBytesFromRAIs;
		} else {
			this.readerPool = readerPool;
			this.readerPoolSeries = readerPoolSeries;
			lvl0DataFetcher = (key) -> this.getBytesFromReaderPool(key);
		}
	}

	private byte[] getBytesFromRAIs(TileIterator.IntsKey key) {
		int r = key.array[0];
		int t = key.array[1];
		int c = key.array[2];
		int z = key.array[3];
		int y = key.array[4];
		int x = key.array[5];

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

	private byte[] getBytesFromReaderPool(TileIterator.IntsKey key) {
		int r = key.array[0];
		int t = key.array[1];
		int c = key.array[2];
		int z = key.array[3];
		int y = key.array[4];
		int x = key.array[5];

		assert r == 0;

		long startX = x * tileX;
		long startY = y * tileY;

		long endX = (x + 1) * (tileX);
		long endY = (y + 1) * (tileY);

		//int	maxX = width; // Before it's the resolution level 0
		//int	maxY = height;

		if (endX > width) endX = width;
		if (endY > height) endY = height;

		try {
			IFormatReader reader = null;
			try {
				reader = readerPool.acquire();
				//System.out.println("oriMetaDataSeries = "+this.readerPoolSeries);
				reader.setSeries(this.readerPoolSeries);
				reader.setResolution(0);
				//System.out.println("W = "+width);
				//System.out.println("H = "+height);
				//System.out.println("Tx = "+(endX-startX));
				//System.out.println("Ty = "+(endY-startY));
				//System.out.println("endX = "+endX);
				//System.out.println("endY = "+endY);
				//System.out.println("endY = "+endY);
				//System.out.println("StartX = "+(startX));
				//System.out.println("StartY = "+(startY));

				return reader.openBytes(
						reader.getIndex(range.getRangeZ().get(z),
								range.getRangeC().get(c),
								range.getRangeT().get(t)),
						(int) (startX), (int) (startY),
						(int) (endX-startX), (int) (endY-startY)
				);
			} finally {
				readerPool.recycle(reader);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;

		/*RandomAccessibleInterval<T> rai =
				ctToRAI.get(range.getRangeC()
						.get(c)).get(range.getRangeT().get(t));
		RandomAccessibleInterval<T> slice = Views.hyperSlice(rai, 2,
				range.getRangeZ().get(z));
		return SourceToByteArray.raiToByteArray(Views.interval(slice,
				new FinalInterval(new long[] { startX, startY }, new long[] { endX - 1,
						endY - 1 })), pixelInstance);*/
	}

	private void computeTile(TileIterator.IntsKey key) throws Exception {
		/*int r = key.array[0];
		localResolution.set(r);
		if (r == 0) {
			computedBlocks.put(key, lvl0DataFetcher.apply(key));
		} else {
			int t = key.array[1];
			int c = key.array[2];
			int z = key.array[3];
			int y = key.array[4];
			int x = key.array[5];

			long startX = x * tileX;
			long startY = y * tileY;

			// Wait for the previous resolution level to be written !
			while (r != currentLevelWritten) {
				synchronized (tileLock) {
					tileLock.wait();
				}
			}

			if ((localResolution.get() == null) || (localResolution.get() != r)) {
				// Need to update the reader : we are now writing the next resolution
				// level
				if (localReader.get() != null) {
					// Closing the previous local reader
					localReader.get().close();
				}
				else {
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

			computedBlocks.put(key, tileByte);
		}*/
		int r = key.array[0];
		int t = key.array[1];
		int c = key.array[2];
		int z = key.array[3];
		int y = key.array[4];
		int x = key.array[5];

		long startX = x * tileX;
		long startY = y * tileY;

		long endX = (x + 1) * (tileX);
		long endY = (y + 1) * (tileY);

		int maxX, maxY;

		if (r != 0) {
			maxX = mapResToWidth.get(r);
			maxY = mapResToHeight.get(r);
		}
		else {
			maxX = width;
			maxY = height;
		}

		if (endX > maxX) endX = maxX;
		if (endY > maxY) endY = maxY;

		if (r == 0) {
			localResolution.set(r);
			/*RandomAccessibleInterval<T> rai =ctToRAI.get(range.getRangeC().get(c)).get(range.getRangeT().get(t));
					//sources[range.getRangeC()
					//.get(c)].getSource(range.getRangeT().get(t), r);
			RandomAccessibleInterval<T> slice = Views.hyperSlice(rai, 2,
					range.getRangeZ().get(z));
			byte[] tileByte = SourceToByteArray.raiToByteArray(Views.interval(slice,
					new FinalInterval(new long[] { startX, startY }, new long[] { endX - 1,
							endY - 1 })), pixelInstance);
			computedBlocks.put(key, tileByte);*/
			computedBlocks.put(key, this.lvl0DataFetcher.apply(key));
		}
		else {
			// Wait for the previous resolution level to be written !
			while (r != currentLevelWritten) {
				synchronized (tileLock) {
					tileLock.wait();
				}
			}

			if ((localResolution.get() == null) || (localResolution.get() != r)) {
				// Need to update the reader : we are now writing the next resolution
				// level
				if (localReader.get() != null) {
					// Closing the previous local reader
					localReader.get().close();
				}
				else {
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

			computedBlocks.put(key, tileByte);
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
				// Closing localReader.get()
				localReader.get().close(); // Close last resolution
			}
			return false;
		}
		else {
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

	OMETiffWriter currentLevelWriter;

	public void export() throws Exception {
		if (writerTask != null) writerTask.setStatusMessage("Exporting " + file
			.getName() + " with " + nThreads + " threads.");
		// Copy metadata from ImagePlus:
		IMetadata omeMeta = MetadataTools.createOMEXMLMetadata();
		IMetadata currentLevelOmeMeta = MetadataTools.createOMEXMLMetadata();

		/*
		public static void populateMetadata(MetadataStore store, int series,
    String imageName, boolean littleEndian, String dimensionOrder,
    String pixelType, int sizeX, int sizeY, int sizeZ, int sizeC, int sizeT,
    int samplesPerPixel)
		 */
		MetadataTools.populateMetadata(omeMeta, dstSeries,
				oriMetadata.getImageName(oriMetaDataSeries), isLittleEndian,oriMetadata.getPixelsDimensionOrder(oriMetaDataSeries).getValue(),
				oriMetadata.getPixelsType(oriMetaDataSeries).toString(), width,height,
				range.getRangeZ().size(),isRGB ? 3:range.getRangeC().size(), range.getRangeT().size(),oriMetadata.getChannelSamplesPerPixel(oriMetaDataSeries,0).getValue());
		omeMeta.setPixelsInterleaved(oriMetadata.getPixelsInterleaved(oriMetaDataSeries), dstSeries);

		MetadataTools.verifyMinimumPopulated(omeMeta, dstSeries);

		MetadataTools.populateMetadata(currentLevelOmeMeta, dstSeries,
				oriMetadata.getImageName(oriMetaDataSeries), isLittleEndian,oriMetadata.getPixelsDimensionOrder(oriMetaDataSeries).getValue(),
				oriMetadata.getPixelsType(oriMetaDataSeries).toString(), width,height,
				range.getRangeZ().size(),isRGB? 3:range.getRangeC().size(), range.getRangeT().size(),oriMetadata.getChannelSamplesPerPixel(oriMetaDataSeries,0).getValue());
		omeMeta.setPixelsInterleaved(oriMetadata.getPixelsInterleaved(oriMetaDataSeries), dstSeries);

		MetadataTools.verifyMinimumPopulated(currentLevelOmeMeta, dstSeries);

		//MetadataConverter.convertMetadata(oriMetadata, omeMeta);
		KheopsHelper.copyFromMetaSeries(oriMetadata,this.oriMetaDataSeries,omeMeta,this.dstSeries);
		MetadataConverter.convertMetadata(omeMeta, currentLevelOmeMeta);

		// IMetadata metaDst, int seriesDst, IMetadata metaSrc, int seriesSrc
		copyChannelsMeta(omeMeta, this.dstSeries, oriMetadata, this.oriMetaDataSeries );
		copyChannelsMeta(currentLevelOmeMeta, this.dstSeries, oriMetadata, this.oriMetaDataSeries);

		// Interleave fix

		for (int r = 0; r < nResolutionLevels - 1; r++) {
			((IPyramidStore) omeMeta).setResolutionSizeX(new PositiveInteger(
				mapResToWidth.get(r + 1)), dstSeries, r + 1);
			((IPyramidStore) omeMeta).setResolutionSizeY(new PositiveInteger(
				mapResToHeight.get(r + 1)), dstSeries, r + 1);
		}

		// setup writer for multiresolution file
		PyramidOMETiffWriter writer = new PyramidOMETiffWriter();
		writer.setMetadataRetrieve(omeMeta);
		writer.setWriteSequentially(true); // Setting this to false can be problematic!
		writer.setBigTiff(true);
		writer.setId(file.getAbsolutePath());
		writer.setSeries(dstSeries);
		writer.setCompression(compression);
		writer.setTileSizeX((int) tileX);
		writer.setTileSizeY((int) tileY);
		writer.setInterleaved(omeMeta.getPixelsInterleaved(dstSeries));
		totalTiles = 0;

		// Count total number of tiles
		for (int r = 0; r < nResolutionLevels; r++) {
			int nXTiles = (int) Math.ceil(mapResToWidth.get(r) / (double) tileX);
			int nYTiles = (int) Math.ceil(mapResToHeight.get(r) / (double) tileY);
			totalTiles += nXTiles * nYTiles;
		}
		totalTiles *= sizeT * sizeC * sizeZ;

		if (writerTask != null) writerTask.setProgressMaximum(totalTiles);

		for (int i = 0; i < nThreads; i++) {
			new Thread(() -> {
				try {
					while (computeNextTile()) {} // loops until no tile needs computation
																				// anymore
				}
				catch (Exception e) {
					e.printStackTrace();
				}
			}).start();
		}

		for (int r = 0; r < nResolutionLevels; r++) {

			int maxX = mapResToWidth.get(r);
			int maxY = mapResToHeight.get(r);
			int nXTiles = (int) Math.ceil(maxX / (double) tileX);
			int nYTiles = (int) Math.ceil(maxY / (double) tileY);

			if (r < nResolutionLevels - 1) { // No need to write the last one
				// Setup current level writer
				currentLevelWriter = new OMETiffWriter();
				currentLevelWriter.setWriteSequentially(true); // Setting this to false
																												// can be problematic!
				currentLevelOmeMeta.setPixelsSizeX(new PositiveInteger(maxX), dstSeries);
				currentLevelOmeMeta.setPixelsSizeY(new PositiveInteger(maxY), dstSeries);

				currentLevelOmeMeta.setPixelsPhysicalSizeX(
						new Length(omeMeta.getPixelsPhysicalSizeX(oriMetaDataSeries).value().doubleValue() * Math.pow(downsample, r + 1), omeMeta.getPixelsPhysicalSizeX(oriMetaDataSeries).unit()), dstSeries);
				currentLevelOmeMeta.setPixelsPhysicalSizeY(
						new Length(omeMeta.getPixelsPhysicalSizeY(oriMetaDataSeries).value().doubleValue() * Math.pow(downsample, r + 1), omeMeta.getPixelsPhysicalSizeX(oriMetaDataSeries).unit()), dstSeries);

				currentLevelOmeMeta.setPixelsDimensionOrder(DimensionOrder.XYCZT, 0);
				currentLevelWriter.setMetadataRetrieve(currentLevelOmeMeta);
				currentLevelWriter.setBigTiff(true);
				currentLevelWriter.setId(getFileName(r));
				currentLevelWriter.setSeries(dstSeries);
				if (compressTempFile) currentLevelWriter.setCompression(compression);
				currentLevelWriter.setTileSizeX((int) tileX);
				currentLevelWriter.setTileSizeY((int) tileY);
				if (r == 0) {
					currentLevelWriter.setInterleaved(true);
				}
				else {
					currentLevelWriter.setInterleaved(false); // !!!! weird. See TestOMETIFFRGBMultiScaleTile
				}
			}

			if (r > 0) writer.setInterleaved(false); // But why the heck ???
			if ((r == 0) && (readerPool!=null) && isRGB) {
				IFormatReader reader = readerPool.acquire();
				reader.setResolution(0);
				reader.setSeries(this.readerPoolSeries);
				writer.setInterleaved(reader.isInterleaved());
				readerPool.recycle(reader);
			}
			logger.debug("Saving resolution size " + r);
			writer.setResolution(r);

			currentLevelWritten = r;
			synchronized (tileLock) { // Notifies that a new resolution level is being written
				tileLock.notifyAll();
			}

			/*IFormatReader reader = readerPool.acquire();
			reader.setResolution(0);
			reader.setSeries(this.readerPoolSeries);

			System.out.println("res level\t "+r+
					"\tome_ori_i\t"+oriMetadata.getPixelsInterleaved(oriMetaDataSeries)+
					"\twriter_i\t"+writer.isInterleaved()+
					"\twriter_c_level_i"+(currentLevelWriter==null? "None":currentLevelWriter.isInterleaved())+
					"\treader\t"+reader.isInterleaved());

			readerPool.recycle(reader);*/

			for (int t = 0; t < sizeT; t++) {
				for (int c = 0; c < sizeC; c++) {
					for (int z = 0; z < sizeZ; z++) {
						for (int y = 0; y < nYTiles; y++) {
							for (int x = 0; x < nXTiles; x++) {
								long startX = x * tileX;
								long startY = y * tileY;
								long endX = (x + 1) * (tileX);
								long endY = (y + 1) * (tileY);
								if (endX > maxX) endX = maxX;
								if (endY > maxY) endY = maxY;
								TileIterator.IntsKey key = new TileIterator.IntsKey(new int[] {
									r, t, c, z, y, x });

								if (nThreads == 0) {
									computeTile(key);
								}
								else {
									while (!computedBlocks.containsKey(key)) {
										synchronized (tileLock) {
											tileLock.wait();
										}
									}
								}

								int plane = t * sizeZ * sizeC + c * sizeZ + z;

								//System.out.println("computedBlocks.get(key).length = "+computedBlocks.get(key).length);
								if (r < nResolutionLevels - 1) {
									currentLevelWriter.saveBytes(plane, computedBlocks.get(key),
										(int) startX, (int) startY, (int) (endX - startX),
										(int) (endY - startY));
								}

								writer.saveBytes(plane, computedBlocks.get(key), (int) startX,
									(int) startY, (int) (endX - startX), (int) (endY - startY));

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
				currentLevelWriter.close();
			}
		}

		if (nThreads == 0) {
			if (writerTask != null) {
				writerTask.setStatusMessage("Closing readers.");
			}
			if (localReader.get() != null) {
				localReader.get().close();
			}
		}
		if (writerTask != null) {
			writerTask.setStatusMessage("Deleting temporary files.");
		}
		for (int r = 0; r < nResolutionLevels - 1; r++) {
			new File(getFileName(r)).delete();
		}
		computedBlocks.clear();
		if (writerTask != null) {
			// Let's do a quick computation based on the following assumption: 5
			// minutes for 100k blocks
			int estimateTimeMin = (int) (5 * totalTiles / 1e5);
			if (estimateTimeMin < 2) {
				writerTask.setStatusMessage(
					"Closing writer... please wait a few minutes.");
			}
			else {
				writerTask.setStatusMessage(
					"Closing writer... please wait, this can take around " +
						estimateTimeMin + " minutes.");
			}
		}
		writer.close();
		if (writerTask != null) writerTask.run(() -> {});
	}

	private String getFileName(int r) {
		return FilenameUtils.removeExtension(file.getAbsolutePath()) + "_lvl_" + r +
			".ome.tiff";
	}

	public static OMETiffExporterBuilder builder() {
		return new OMETiffExporterBuilder();
	}

	public static class OMETiffExporterBuilder {

		public static Data.DataBuilder defineData() {
			return new Data.DataBuilder();
		}
		public static class Data<T> {

			public static <T> boolean validPixelType(T t) {
				Set<Class<? extends Type<?>>> validClasses = new HashSet<>();
				validClasses.add(UnsignedByteType.class);
				validClasses.add(UnsignedShortType.class);
				validClasses.add(ARGBType.class);
				validClasses.add(FloatType.class);
				return validClasses.contains(t.getClass());
			}

			protected final int pixelsSizeX, pixelsSizeY, pixelsSizeZ, pixelsSizeC, pixelsSizeT;
			protected final Map<Integer, Map<Integer, RandomAccessibleInterval<T>>> ctToRAI;

			protected final ResourcePool<IFormatReader> readerPool;
			protected final int readerPoolSeries;

			protected final T pixelInstance;

			private Data(DataBuilder<T> builder) {
				this.pixelsSizeX = builder.nPixelX;
				this.pixelsSizeY = builder.nPixelY;
				this.pixelsSizeZ = builder.nPixelZ;
				this.pixelsSizeC = builder.nChannels;
				this.pixelsSizeT = builder.nTimePoints;
				this.ctToRAI = builder.ctToRAI;
				this.pixelInstance = builder.pixelInstance;
				this.readerPoolSeries = builder.readerPoolSeries;
				this.readerPool = builder.readerPool;
			}
			public static class DataBuilder<T> {
				private int nPixelX = -1, nPixelY = -1, nPixelZ = -1;
				private int nChannels = -1, nTimePoints = -1;
				private Map<Integer, Map<Integer, RandomAccessibleInterval<T>>> ctToRAI = new HashMap<>();
				RandomAccessibleInterval<T> model;
				T pixelInstance;

				ResourcePool<IFormatReader> readerPool;
				int readerPoolSeries;


				public DataBuilder<T> setReaderPool(ResourcePool<IFormatReader> readerPool, int series) {
					this.readerPool = readerPool;
					this.readerPoolSeries = series;
					return this;
				}

				public DataBuilder<T> put(int channel, Source<T> source) throws UnsupportedOperationException {
					int t = 0;
					while (source.isPresent(t)) {
						put3DRAI(channel, t, source.getSource(t,0));
						t++;
					}
					return this;
				}

				public DataBuilder<T> put(Source<T> source) throws UnsupportedOperationException {
					return put(0, source);
				}

				public DataBuilder<T> put(SourceAndConverter<T>[] sources) throws UnsupportedOperationException {
					for (int c = 0; c<sources.length;c++) {
						put(c, sources[c]);
					}
					return this;
				}

				public DataBuilder<T> put(int c, SourceAndConverter<T> source) throws UnsupportedOperationException {
					put(c, source.getSpimSource());
					return this;
				}

				public DataBuilder<T> put3DRAI(int channel, int timepoint, RandomAccessibleInterval<T> rai) throws UnsupportedOperationException {
					validate(channel, timepoint);
					validate(rai);
					ctToRAI.get(channel).put(timepoint, rai);
					return this;
				}

				public DataBuilder<T> put3DRAI(RandomAccessibleInterval<T> rai) throws UnsupportedOperationException {
					put3DRAI(0,0,rai);
					return this;
				}


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
					// Can't test the type...
				}
			}
		}
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


				public MetaDataBuilder imageName(String imageName) {
					omeMeta.setImageName(imageName, series);
					return this;
				}

				public MetaDataBuilder channelName(int channel, String channelName) {
					omeMeta.setChannelID("Channel:0:" + channel, series, channel);
					omeMeta.setChannelName(channelName, series, channel);
					return this;
				}

				public MetaDataBuilder channelColor(int channel, int r, int g, int b, int a) {
					omeMeta.setChannelColor(new Color(r, g, b, a), series, channel);
					return this;
				}

				public MetaDataBuilder applyOnMeta(Function<IMetadata, IMetadata> f) {
					omeMeta = f.apply(this.omeMeta);
					return this;
				}

				public MetaDataBuilder planePosition(Length originX, Length originY, Length originZ, int planeIndex) {
					omeMeta.setPlanePositionX(originX, series, planeIndex);
					omeMeta.setPlanePositionY(originY, series, planeIndex);
					omeMeta.setPlanePositionZ(originZ, series, planeIndex);
					return this;
				}

				public MetaDataBuilder planePositionMicrometer(double originX, double originY, double originZ, int planeIndex) {
					return planePosition(new Length(originX, UNITS.MICROMETER),
							new Length(originY, UNITS.MICROMETER),
							new Length(originZ, UNITS.MICROMETER),
							planeIndex
					);
				}

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
					omeMeta.setPixelsPhysicalSizeX(physicalSizeY,series);
					omeMeta.setPixelsPhysicalSizeZ(physicalSizeZ, series);
					return this;
				}

				public MetaDataBuilder voxelPhysicalSizeMicrometer(double physicalSizeXInMicrometer, double physicalSizeYInMicrometer, double physicalSizeZInMicrometer) {
					return voxelPhysicalSize(new Length(physicalSizeXInMicrometer, UNITS.MICROMETER),
							new Length(physicalSizeYInMicrometer, UNITS.MICROMETER),
							new Length(physicalSizeZInMicrometer, UNITS.MICROMETER));
				}

				public MetaDataBuilder voxelPhysicalSizeMillimeter(double physicalSizeXInMillimeter, double physicalSizeYInMillimeter, double physicalSizeZInMillimeter) {
					return voxelPhysicalSize(new Length(physicalSizeXInMillimeter, UNITS.MILLIMETER),
							new Length(physicalSizeYInMillimeter, UNITS.MILLIMETER),
							new Length(physicalSizeZInMillimeter, UNITS.MILLIMETER));
				}

				public WriterOptions.WriterOptionsBuilder defineWriteOptions() {
					return new WriterOptions.WriterOptionsBuilder(new MetaData(this), data);
				}

			}
		}
		public static class WriterOptions {

			final public int nThreads;
			final public String rangeC;
			final public String rangeZ;
			final public String rangeT;
			final public String path;
			final public int tileX; // = no tiling
			final public int tileY; // = no tiling
			final public String compression;
			final public boolean compressTempFiles;
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
				this.compressTempFiles = builder.compressTempFiles;
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
				String compression = "LZW";
				boolean compressTempFiles = true;
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
				 * @return the builder
				 */
				public WriterOptionsBuilder jpg() {
					this.compression = CompressionType.JPEG.getCompression();
					return this;
				}

				public WriterOptionsBuilder monitor(TaskService taskService) {
					this.taskService = taskService;
					return this;
				}

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

				public WriterOptionsBuilder compressTemporaryFiles(boolean compressTempFile) {
					this.compressTempFiles = compressTempFile;
					return this;
				}

				public WriterOptionsBuilder savePath(String path) {
					this.filePath = path;
					return this;
				}

				public OMETiffExporter create() throws Exception {
					if ((filePath == null)||(filePath.trim().equals(""))) {
						throw new IOException("Invalid path file");
					}

					if (new File(filePath).exists()) {
						throw new IOException("Path "+filePath+" already exists");
					}

					WriterOptions wOpts = new WriterOptions(this);
					return new OMETiffExporter(data.ctToRAI, metaData.omeMeta, metaData.series, wOpts, data.readerPool, data.readerPoolSeries);
				}
			}
		}
	}
}
