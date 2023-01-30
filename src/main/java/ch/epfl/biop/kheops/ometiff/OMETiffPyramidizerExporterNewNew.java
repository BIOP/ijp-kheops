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

import ch.epfl.biop.kheops.CZTRange;
import loci.common.image.IImageScaler;
import loci.formats.MetadataTools;
import loci.formats.in.OMETiffReader;
import loci.formats.meta.IMetadata;
import loci.formats.meta.IPyramidStore;
import loci.formats.out.OMETiffWriter;
import loci.formats.out.PyramidOMETiffWriter;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.view.Views;
import ome.units.quantity.Length;
import ome.xml.meta.MetadataConverter;
import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.primitives.PositiveInteger;
import org.apache.commons.io.FilenameUtils;
import org.scijava.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

public class OMETiffPyramidizerExporterNewNew<T extends NumericType<T>> {

	private static final Logger logger = LoggerFactory.getLogger(
		OMETiffPyramidizerExporterNewNew.class);

	// ------------ Data and metadata
	final Map<Integer, Map<Integer, RandomAccessibleInterval<T>>> ctToRAI;
	final IMetadata oriMetadata;
	final int oriMetaDataSeries;
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
	final T pixelType;
	final int width, height, sizeT, sizeC, sizeZ;
	final Map<Integer, Integer> mapResToWidth = new HashMap<>();
	final Map<Integer, Integer> mapResToHeight = new HashMap<>();
	final Map<Integer, Integer> resToNY = new HashMap<>();
	final Map<Integer, Integer> resToNX = new HashMap<>();

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

	protected OMETiffPyramidizerExporterNewNew(
			// Image data and metadata, + czt optional subsetting
			Map<Integer, Map<Integer, RandomAccessibleInterval<T>>> ctToRAI, // Image data
			IMetadata originalOmeMeta, int originalSeries,
			// Writing options
			OMETiffExportBuilder.WriterOptions writerSettings) throws Exception {
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
		pixelType = model.getAt(0,0,0);

		isRGB = pixelType instanceof ARGBType;

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
		System.out.println("#C"+sizeC+"#Z"+sizeZ+"#T"+sizeT);

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
	}

	private void computeTile(TileIterator.IntsKey key) throws Exception {
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
			RandomAccessibleInterval<T> rai =
					ctToRAI.get(range.getRangeC()
							.get(c)).get(range.getRangeT().get(t));
			RandomAccessibleInterval<T> slice = Views.hyperSlice(rai, 2,
				range.getRangeZ().get(z));
			byte[] tileByte = SourceToByteArray.raiToByteArray(Views.interval(slice,
				new FinalInterval(new long[] { startX, startY }, new long[] { endX - 1,
					endY - 1 })), pixelType);
			computedBlocks.put(key, tileByte);
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

	boolean isLittleEndian = false;
	boolean isRGB = false;
	volatile boolean isInterleaved = false;
	boolean isFloat = false;
	int bytesPerPixel;

	private void populateOmeMeta(IMetadata metaDst, int seriesDst, IMetadata metaSrc, int seriesSrc) {
		if (isRGB) {
			MetadataConverter.convertChannels(metaSrc,seriesSrc,0,metaDst,seriesDst,0,false);
		} else for (int c = 0; c < sizeC; c++) {
			int srcC = range.getRangeC().get(c);
			System.out.println("srcC = "+srcC+" dstC = "+c);
			MetadataConverter.convertChannels(metaSrc,seriesSrc,srcC,metaDst,seriesDst,c,false);
		}
	}

	OMETiffWriter currentLevelWriter;

	public void export() throws Exception {
		if (writerTask != null) writerTask.setStatusMessage("Exporting " + file
			.getName() + " with " + nThreads + " threads.");
		// Copy metadata from ImagePlus:
		IMetadata omeMeta = MetadataTools.createOMEXMLMetadata();
		IMetadata currentLevelOmeMeta = MetadataTools.createOMEXMLMetadata();

		isLittleEndian = true;
		isInterleaved = isRGB;

		MetadataConverter.convertMetadata(oriMetadata, omeMeta);
		MetadataConverter.convertMetadata(oriMetadata, currentLevelOmeMeta);
		// IMetadata metaDst, int seriesDst, IMetadata metaSrc, int seriesSrc
		populateOmeMeta(omeMeta, this.dstSeries, oriMetadata, this.oriMetaDataSeries );
		populateOmeMeta(currentLevelOmeMeta, this.dstSeries, oriMetadata, this.oriMetaDataSeries);

		for (int r = 0; r < nResolutionLevels - 1; r++) {
			((IPyramidStore) omeMeta).setResolutionSizeX(new PositiveInteger(
				mapResToWidth.get(r + 1)), dstSeries, r + 1);
			((IPyramidStore) omeMeta).setResolutionSizeY(new PositiveInteger(
				mapResToHeight.get(r + 1)), dstSeries, r + 1);
		}

		// setup writer for multiresolution file
		PyramidOMETiffWriter writer = new PyramidOMETiffWriter();
		writer.setMetadataRetrieve(omeMeta);
		writer.setWriteSequentially(true); // Setting this to false can be
																				// problematic!
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
					currentLevelWriter.setInterleaved(false); // !!!! weird. See
																										// TestOMETIFFRGBMultiScaleTile
				}
			}

			if (r > 0) writer.setInterleaved(false); // But why the heck ???

			logger.debug("Saving resolution size " + r);
			writer.setResolution(r);

			currentLevelWritten = r;
			synchronized (tileLock) { // Notifies that a new resolution level is being
																// written
				tileLock.notifyAll();
			}

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

}
