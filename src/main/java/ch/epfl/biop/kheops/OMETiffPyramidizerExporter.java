package ch.epfl.biop.kheops;

import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import loci.common.image.IImageScaler;
import loci.formats.MetadataTools;
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
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.units.unit.Unit;
import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.enums.PixelType;
import ome.xml.model.primitives.Color;
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
 * Export an array of {@link SourceAndConverter} into a OME-TIFF file, potentially multiresolution,
 * if set.
 *
 * The array represents different channels. All Sources should have the same size in XYZCT
 * for the highest resolution level (other resolution levels, if any, are ignored).
 *
 * The builder {@link OMETiffPyramidizerExporter.Builder} should be used to export the sources. (private constructor)
 *
 * Parallelization can occur at the reading level, with a number of threads set with
 * {@link OMETiffPyramidizerExporter.Builder#nThreads(int)}. Writing to HDD is serial.
 *
 * For big planes, tiled images can be saved with {@link OMETiffPyramidizerExporter.Builder#tileSize(int, int)}
 *
 * Lzw compression possible {@link OMETiffPyramidizerExporter.Builder#lzw()}
 *
 * To make a 'pyramid' (= sub resolution levels), specify the number of resolution levels with
 * {@link Builder#nResolutionLevels(int)} and the downscaling with {@link Builder#downsample(int)}.
 * Each resolution levels averages the pixels from the previous resolution level by using
 * {@link AverageImageScaler}.
 *
 * This class should not be memory hungry, the number of data kept into ram should never exceed
 * (max queue size + nThreads) * tile size in bytes, even with really large dataset.
 *
 * A {@link Task} object can be given in {@link OMETiffPyramidizerExporter.Builder#monitor(Task)}to monitor the saving and
 * also to cancel the export (TODO for cancellation).
 *
 * The RAM occupation depends on the caching mechanism (if any) in the input {@link SourceAndConverter} array.
 *
 * @author Nicolas Chiaruttini, EPFL, 2022
 */

// See https://forum.image.sc/t/ome-tiff-saving-optimisation-reading-from-the-file-thats-being-written/65705
// for a discussion about pyramid optimisation -> in the end the file is written two times - one
// for the final ome tiff, and another one which contains the current resolution level, that will be used
// for building the next resolution level
// original script https://github.com/ome/bio-formats-examples/blob/master/src/main/java/GeneratePyramidResolutions.java
// RAAAAH https://forum.image.sc/t/save-ome-tiff-as-8-bit-rgb-for-qupath/61281/3

public class OMETiffPyramidizerExporter {

    private static final Logger logger = LoggerFactory.getLogger(OMETiffPyramidizerExporter.class);

    final long tileX, tileY;
    final int nResolutionLevels;
    final int downsample;

    final File file;
    final Source[] sources;
    final String name;
    final ColorConverter[] converters;
    final Unit<Length> unit;
    final String compression;
    final AtomicLong writtenTiles = new AtomicLong();
    long totalTiles;

    final int nChannels;
    final NumericType pixelType;
    final int width, height, sizeT, sizeC, sizeZ;
    final double[] voxelSizes = new double[3];
    final RealPoint origin = new RealPoint(3);
    final Map<Integer, Integer> mapResToWidth = new HashMap<>();
    final Map<Integer, Integer> mapResToHeight = new HashMap<>();

    final Map<TileIterator.IntsKey, byte[]> computedBlocks;

    final Map<Integer, Integer> resToNY = new HashMap<>();
    final Map<Integer, Integer> resToNX = new HashMap<>();
    final TileIterator tileIterator;
    final int nThreads;
    final Task task;

    volatile Object tileLock = new Object();

    final boolean overridePixelSize;
    final double voxSX, voxSY, voxSZ;

    public OMETiffPyramidizerExporter(Source[] sources,
                                      ColorConverter[] converters,
                                      Unit<Length> unit,
                                      File file,
                                      int tileX,
                                      int tileY,
                                      int nResolutionLevels,
                                      int downsample,
                                      String compression,
                                      String name,
                                      int nThreads,
                                      int maxTilesInQueue,
                                      Task task,
                                      boolean overridePixelSize,
                                      double voxSX, double voxSY, double voxSZ) {
        this.overridePixelSize = overridePixelSize;
        this.voxSX = voxSX;
        this.voxSY = voxSY;
        this.voxSZ = voxSZ;
        this.task = task;
        Source model = sources[0];
        this.tileX = tileX;
        this.tileY = tileY;
        this.downsample = downsample;
        this.nResolutionLevels = nResolutionLevels;

        this.unit = unit;
        this.file = file;
        this.sources = sources;
        this.name = name;
        this.converters = converters;
        this.compression = compression;
        writtenTiles.set(0);

        // Prepare = gets all dimensions
        nChannels = sources.length;

        if (!(model.getType() instanceof NumericType)) throw new UnsupportedOperationException("Can't export pixel type "+model.getType().getClass());

        pixelType = (NumericType) model.getType();

        width = (int) model.getSource(0,0).max(0)+1;
        height = (int) model.getSource(0,0).max(1)+1;

        sizeZ = (int) model.getSource(0,0).max(2)+1;
        sizeT = getMaxTimepoint(model);
        sizeC = sources.length;

        AffineTransform3D mat = new AffineTransform3D();
        model.getSourceTransform(0,0, mat);

        double[] m = mat.getRowPackedCopy();

        for(int d = 0; d < 3; ++d) {
            voxelSizes[d] = Math.sqrt(m[d] * m[d] + m[d + 4] * m[d + 4] + m[d + 8] * m[d + 8]);
        }

        AffineTransform3D transform3D = new AffineTransform3D();
        model.getSourceTransform(0,0, transform3D);
        transform3D.apply(origin, origin);

        mapResToWidth.put(0,width);
        mapResToHeight.put(0,height);

        for (int i= 0;i<nResolutionLevels-1;i++) {
            mapResToWidth.put(i+1,(int) (width/Math.pow(downsample,i+1)));
            mapResToHeight.put(i+1,(int) (height/Math.pow(downsample,i+1)));
        }

        // One iteration to count the number of tiles

        // some assertion : same dimensions for all  nr and c and t
        for (int r = 0; r < nResolutionLevels; r++) {
            int nXTiles;
            int nYTiles;
            int maxX, maxY;
            if (r!=0) {
                maxX = mapResToWidth.get(r);
                maxY = mapResToHeight.get(r);
            } else {
                maxX = width;
                maxY = height;
            }
            nXTiles = (int) Math.ceil(maxX/(double)tileX);
            nYTiles = (int) Math.ceil(maxY/(double)tileY);
            resToNX.put(r,nXTiles);
            resToNY.put(r,nYTiles);
        }

        tileIterator = new TileIterator(nResolutionLevels, sizeT, sizeC, sizeZ, resToNY, resToNX, maxTilesInQueue);
        this.nThreads = nThreads;
        computedBlocks = new ConcurrentHashMap<>(nThreads*3+1); // should be enough to avoiding overlap of hash
    }

    ThreadLocal<OMETiffReader> localReader = new ThreadLocal<>(); // One object per thread
    ThreadLocal<IImageScaler> localScaler = new ThreadLocal<>();
    ThreadLocal<Integer> localResolution = new ThreadLocal<>();

    volatile int currentLevelWritten = -1;

    private void computeTile(TileIterator.IntsKey key) throws Exception {
        int r = key.array[0];
        int t = key.array[1];
        int c = key.array[2];
        int z = key.array[3];
        int y = key.array[4];
        int x = key.array[5];

        long startX = x*tileX;
        long startY = y*tileY;

        long endX = (x+1)*(tileX);
        long endY = (y+1)*(tileY);

        int maxX, maxY;

        if (r!=0) {
            maxX = mapResToWidth.get(r);
            maxY = mapResToHeight.get(r);
        } else {
            maxX = width;
            maxY = height;
        }

        if (endX>maxX) endX = maxX;
        if (endY>maxY) endY = maxY;

        if (r==0) {
            localResolution.set(r);
            RandomAccessibleInterval<NumericType<?>> rai = sources[c].getSource(t, r);
            RandomAccessibleInterval<NumericType<?>> slice = Views.hyperSlice(rai, 2, z);
            byte[] tileByte = SourceToByteArray.raiToByteArray(
                    Views.interval(slice, new FinalInterval(new long[]{startX, startY}, new long[]{endX - 1, endY - 1})),
                    pixelType);
            computedBlocks.put(key,tileByte);
        } else {
            // Wait for the previous resolution level to be written !
            while (r!=currentLevelWritten) {
                synchronized (tileLock) {
                    tileLock.wait();
                }
            }

            if ((localResolution.get()==null)||(localResolution.get()!=r)) {
                // Need to update the reader : we are now writing the next resolution level
                if (localReader.get()!=null) {
                    //Closing the previous local reader
                    localReader.get().close();
                } else {
                    localScaler.set(new AverageImageScaler());
                }
                OMETiffReader reader = new OMETiffReader();
                IMetadata omeMeta = MetadataTools.createOMEXMLMetadata();
                reader.setMetadataStore(omeMeta);
                reader.setId(getFileName(r-1));
                reader.setSeries(0);
                localReader.set(reader);
                localResolution.set(r);
            }

            int plane = t * sizeZ * sizeC + c * sizeZ + z;

            long effTileSizeX = tileX * downsample;
            if (((startX * downsample) + effTileSizeX) >= mapResToWidth.get(r-1)) {
                effTileSizeX = mapResToWidth.get(r-1) - (startX * downsample);
            }

            long effTileSizeY = tileY * downsample;
            if (((startY * downsample) + effTileSizeY) >= mapResToHeight.get(r-1)) {
                effTileSizeY = mapResToHeight.get(r-1) - (startY * downsample);
            }

            byte[] tileBytePreviousLevel = localReader.get()
                    .openBytes(plane, (int)(startX * downsample), (int)(startY * downsample), (int)(effTileSizeX), (int)(effTileSizeY));

            byte[] tileByte = localScaler.get().downsample(tileBytePreviousLevel, (int) effTileSizeX, (int) effTileSizeY, downsample,
                    bytesPerPixel, isLittleEndian,
                    isFloat, isRGB?3:1, false);

            computedBlocks.put(key,tileByte);
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
            if (localReader.get()!=null) {
                // Closing localReader.get()
                localReader.get().close(); // Close last resolution
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

    boolean isLittleEndian = false;
    boolean isRGB = false;
    volatile boolean isInterleaved = false;
    boolean isFloat = false;
    int bytesPerPixel;

    private void populateOmeMeta(IMetadata meta, int series) {

        meta.setImageID("Image:"+series, series);
        meta.setPixelsID("Pixels:"+series, series);
        meta.setImageName(name, series);

        if (pixelType instanceof UnsignedShortType) {
            meta.setPixelsType(PixelType.UINT16, series);
            bytesPerPixel = 2;
            isInterleaved = false;
            meta.setPixelsInterleaved(false, series);
            meta.setPixelsDimensionOrder(DimensionOrder.XYZCT, series);
        } else if (pixelType instanceof UnsignedByteType) {
            meta.setPixelsType(PixelType.UINT8, series);
            bytesPerPixel = 1;
            isInterleaved = false;
            meta.setPixelsInterleaved(false, series);
            meta.setPixelsDimensionOrder(DimensionOrder.XYZCT, series);
        } else if (pixelType instanceof FloatType) {
            meta.setPixelsType(PixelType.FLOAT, series);
            bytesPerPixel = 4;
            isFloat = true;
            isInterleaved = false;
            meta.setPixelsInterleaved(false, series);
            meta.setPixelsDimensionOrder(DimensionOrder.XYZCT, series);
        } else if (pixelType instanceof ARGBType) {
            isInterleaved = true;
            isRGB = true;
            bytesPerPixel = 1;
            meta.setPixelsType(PixelType.UINT8, series);
            meta.setPixelsDimensionOrder(DimensionOrder.XYCZT, series);
            meta.setPixelsInterleaved(true, series);
        } else {
            throw new UnsupportedOperationException("Unhandled pixel type class: "+pixelType.getClass().getName());
        }

        meta.setPixelsBigEndian(!isLittleEndian, series);

        // Set resolutions
        meta.setPixelsSizeX(new PositiveInteger(width), series);
        meta.setPixelsSizeY(new PositiveInteger(height), series);
        meta.setPixelsSizeZ(new PositiveInteger(sizeZ), series);
        meta.setPixelsSizeT(new PositiveInteger(sizeT), series);
        meta.setPixelsSizeC(new PositiveInteger(isRGB?nChannels*3:nChannels), series);

        if (isRGB) {
            meta.setChannelID("Channel:0", series, 0);
            meta.setChannelName("Channel_0", series, 0);
            meta.setChannelSamplesPerPixel(new PositiveInteger(3), series, 0);
        } else {
            for (int c = 0; c < nChannels; c++) {
                meta.setChannelID("Channel:0:" + c, series, c);
                meta.setChannelSamplesPerPixel(new PositiveInteger(1), series, c);
                int colorCode = converters[c].getColor().get();
                int colorRed = ARGBType.red(colorCode);
                int colorGreen = ARGBType.green(colorCode);
                int colorBlue = ARGBType.blue(colorCode);
                int colorAlpha = ARGBType.alpha(colorCode);
                meta.setChannelColor(new Color(colorRed, colorGreen, colorBlue, colorAlpha), series, c);
                meta.setChannelName("Channel_"+c, series, c);
            }
        }

        if (overridePixelSize) {
            meta.setPixelsPhysicalSizeX(new Length(voxSX, unit), series);
            meta.setPixelsPhysicalSizeY(new Length(voxSY, unit), series);
            meta.setPixelsPhysicalSizeZ(new Length(voxSZ, unit), series);
        } else {
            meta.setPixelsPhysicalSizeX(new Length(voxelSizes[0], unit), series);
            meta.setPixelsPhysicalSizeY(new Length(voxelSizes[1], unit), series);
            meta.setPixelsPhysicalSizeZ(new Length(voxelSizes[2], unit), series);
        }
        // set Origin in XYZ
        // TODO : check if enough or other planes need to be set ?
        meta.setPlanePositionX(new Length(origin.getDoublePosition(0), unit),0,0);
        meta.setPlanePositionY(new Length(origin.getDoublePosition(1), unit),0,0);
        meta.setPlanePositionZ(new Length(origin.getDoublePosition(2), unit),0,0);
    }

    OMETiffWriter currentLevelWriter;

    public void export() throws Exception {
        if (task!=null) task.setStatusMessage("Exporting "+file.getName()+" with "+nThreads+" threads.");
        // Copy metadata from ImagePlus:
        IMetadata omeMeta = MetadataTools.createOMEXMLMetadata();
        IMetadata currentLevelOmeMeta = MetadataTools.createOMEXMLMetadata();

        isLittleEndian = false;
        isRGB = false;
        isInterleaved = false;

        int series = 0;
        populateOmeMeta(omeMeta,series);
        populateOmeMeta(currentLevelOmeMeta, series);

        for (int r= 0;r<nResolutionLevels-1;r++) {
            ((IPyramidStore)omeMeta).setResolutionSizeX(new PositiveInteger(mapResToWidth.get(r+1)),series, r + 1);
            ((IPyramidStore)omeMeta).setResolutionSizeY(new PositiveInteger(mapResToHeight.get(r+1)),series, r + 1);
        }

        // setup writer for multiresolution file
        PyramidOMETiffWriter writer = new PyramidOMETiffWriter();
        writer.setMetadataRetrieve(omeMeta);
        writer.setWriteSequentially(true); // Setting this to false can be problematic!
        writer.setBigTiff(true);
        writer.setId(file.getAbsolutePath());
        writer.setSeries(series);
        writer.setCompression(compression);
        writer.setTileSizeX((int)tileX);
        writer.setTileSizeY((int)tileY);
        writer.setInterleaved(omeMeta.getPixelsInterleaved(series));
        totalTiles = 0;

        // Count total number of tiles
        for (int r = 0; r < nResolutionLevels; r++) {
            int nXTiles = (int) Math.ceil(mapResToWidth.get(r)/(double)tileX);
            int nYTiles = (int) Math.ceil(mapResToHeight.get(r)/(double)tileY);
            totalTiles+=nXTiles*nYTiles;
        }
        totalTiles *= sizeT*sizeC*sizeZ;

        if (task!=null) task.setProgressMaximum(totalTiles);

        for (int i=0;i<nThreads;i++) {
            new Thread(() -> {
                try {
                    while (computeNextTile()) {
                    } // loops until no tile needs computation  anymore
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        }

        for (int r = 0; r < nResolutionLevels; r++) {
            currentLevelWritten = r;

            synchronized (tileLock) { // Notifies that a new resolution level is being written
                tileLock.notifyAll();
            }
            int maxX = mapResToWidth.get(r);
            int maxY = mapResToHeight.get(r);
            int nXTiles = (int) Math.ceil(maxX/(double)tileX);
            int nYTiles = (int) Math.ceil(maxY/(double)tileY);

            if (r<nResolutionLevels-1) { // No need to write the last one
                // Setup current level writer
                currentLevelWriter = new OMETiffWriter();
                currentLevelWriter.setWriteSequentially(true); // Setting this to false can be problematic!
                currentLevelOmeMeta.setPixelsSizeX(new PositiveInteger(maxX), series);
                currentLevelOmeMeta.setPixelsSizeY(new PositiveInteger(maxY), series);
                currentLevelOmeMeta.setPixelsPhysicalSizeX(new Length(voxelSizes[0]*Math.pow(downsample,r+1), unit), series);
                currentLevelOmeMeta.setPixelsPhysicalSizeY(new Length(voxelSizes[1]*Math.pow(downsample,r+1), unit), series);
                currentLevelOmeMeta.setPixelsDimensionOrder(DimensionOrder.XYCZT,0);
                currentLevelWriter.setMetadataRetrieve(currentLevelOmeMeta);
                currentLevelWriter.setBigTiff(true);
                currentLevelWriter.setId(getFileName(r));
                currentLevelWriter.setSeries(series);
                //currentLevelWriter.setCompression(compression); -> uncompressed -> faster
                currentLevelWriter.setTileSizeX((int) tileX);
                currentLevelWriter.setTileSizeY((int) tileY);
                if (r==0) {
                    currentLevelWriter.setInterleaved(true);
                } else {
                    currentLevelWriter.setInterleaved(false); // !!!! weird. See TestOMETIFFRGBMultiScaleTile
                }
            }

            if (r>0) writer.setInterleaved(false); // But why the heck ???

            logger.debug("Saving resolution size " + r);
            writer.setResolution(r);

            for (int t=0;t<sizeT;t++) {
                for (int c=0;c<sizeC;c++) {
                    for (int z=0;z<sizeZ;z++) {
                        for (int y=0; y<nYTiles; y++) {
                            for (int x=0; x<nXTiles; x++) {
                                long startX = x * tileX;
                                long startY = y * tileY;
                                long endX = (x + 1) * (tileX);
                                long endY = (y + 1) * (tileY);
                                if (endX > maxX) endX = maxX;
                                if (endY > maxY) endY = maxY;
                                TileIterator.IntsKey key = new TileIterator.IntsKey(new int[]{r, t, c, z, y, x});

                                if (nThreads == 0) {
                                    computeTile(key);
                                } else {
                                    while (!computedBlocks.containsKey(key)) {
                                        synchronized (tileLock) {
                                            tileLock.wait();
                                        }
                                    }
                                }

                                int plane = t * sizeZ * sizeC + c * sizeZ + z;

                                if (r<nResolutionLevels-1) {
                                    currentLevelWriter.saveBytes(plane, computedBlocks.get(key), (int) startX, (int) startY, (int) (endX - startX), (int) (endY - startY));
                                }

                                writer.saveBytes(plane, computedBlocks.get(key), (int)startX, (int)startY, (int)(endX-startX), (int)(endY-startY));

                                computedBlocks.remove(key);
                                tileIterator.decrementQueue();
                                if (task!=null) task.setProgressValue(writtenTiles.incrementAndGet());
                            }
                        }
                    }
                }
            }
            if (r<nResolutionLevels-1) {
                currentLevelWriter.close();
            }
        }
        if (nThreads == 0) {
            if (localReader.get()!=null) {
                localReader.get().close();
            }
        }

        for (int r = 0; r < nResolutionLevels-1; r++) {
            new File(getFileName(r)).delete();
        }

        writer.close();
        computedBlocks.clear();

        if (task!=null) task.run(()->{});
    }

    private String getFileName(int r) {
        return FilenameUtils.removeExtension(file.getAbsolutePath())+"_lvl_"+r+".ome.tiff";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        Unit unit = UNITS.MILLIMETER;
        String path;
        int tileX = Integer.MAX_VALUE; // = no tiling
        int tileY = Integer.MAX_VALUE; // = no tiling
        String compression = "Uncompressed";
        int nThreads = 0;
        int maxTilesInQueue = 10;
        transient Task task = null;
        int nResolutions = 5;
        int downSample = 2;
        boolean overridePixSize = false;
        double voxSizeX = -1;
        double voxSizeY = -1;
        double voxSizeZ = -1;

        public Builder tileSize(int tileX, int tileY) {
            this.tileX = tileX;
            this.tileY = tileY;
            return this;
        }

        public Builder downsample(int downsample) {
            this.downSample = downsample;
            return this;
        }

        public Builder nResolutionLevels(int nResolutions) {
            this.nResolutions = nResolutions;
            return this;
        }

        public Builder lzw() {
            this.compression = "LZW";
            return this;
        }

        public Builder monitor(Task task) {
            this.task = task;
            return this;
        }

        public Builder maxTilesInQueue(int max) {
            this.maxTilesInQueue = max;
            return this;
        }

        public Builder compression(String compression) {
            this.compression = compression;
            return this;
        }

        public Builder savePath(String path) {
            this.path = path;
            return this;
        }

        public Builder millimeter() {
            this.unit = UNITS.MILLIMETER;
            return this;
        }

        public Builder micrometer() {
            this.unit = UNITS.MICROMETER;
            return this;
        }

        public Builder unit(Unit unit) {
            this.unit = unit;
            return this;
        }

        public Builder nThreads(int nThreads) {
            this.nThreads = nThreads;
            return this;
        }

        public void setPixelSize(double sX, double sY, double sZ) {
            overridePixSize = true;
            voxSizeX = sX;
            voxSizeY = sY;
            voxSizeZ = sZ;
        }

        public OMETiffPyramidizerExporter create(SourceAndConverter... sacs) {
            if (path == null) throw new UnsupportedOperationException("Path not specified");
            Source[] sources = new Source[sacs.length];
            ColorConverter[] converters = new ColorConverter[sacs.length];

            for (int i = 0;i<sacs.length;i++) {
                sources[i] = sacs[i].getSpimSource();
                converters[i] = (ColorConverter) sacs[i].getConverter();
            }
            File f = new File(path);
            String imageName = FilenameUtils.removeExtension(f.getName());
            return new OMETiffPyramidizerExporter(
                    sources,
                    converters,
                    unit,
                    f,
                    tileX, tileY,
                    nResolutions,
                    downSample,
                    compression,
                    imageName,
                    nThreads,
                    maxTilesInQueue,
                    task,
                    overridePixSize,
                    voxSizeX,
                    voxSizeY,
                    voxSizeZ);
        }


    }


    public static int getMaxTimepoint(Source<?> source) {
        if (!source.isPresent(0)) {
            return 0;
        } else {
            int nFrames = 1;
            int iFrame = 1;

            int previous;
            for(previous = iFrame; iFrame < 1073741823 && source.isPresent(iFrame); iFrame *= 2) {
                previous = iFrame;
            }

            if (iFrame > 1) {
                for(int tp = previous; tp < iFrame + 1; ++tp) {
                    if (!source.isPresent(tp)) {
                        nFrames = tp;
                        break;
                    }
                }
            }

            return nFrames;
        }
    }

}
