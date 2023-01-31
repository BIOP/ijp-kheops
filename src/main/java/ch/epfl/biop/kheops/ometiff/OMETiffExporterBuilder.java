package ch.epfl.biop.kheops.ometiff;

import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import ome.codecs.CompressionType;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.units.quantity.Time;
import ome.xml.meta.MetadataConverter;
import ome.xml.meta.MetadataRetrieve;
import ome.xml.meta.MetadataStore;
import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.enums.PixelType;
import ome.xml.model.primitives.Color;
import ome.xml.model.primitives.PositiveInteger;
import org.scijava.task.TaskService;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class OMETiffExporterBuilder {

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
        public static class DataBuilder<T> {
            private int nPixelX = -1, nPixelY = -1, nPixelZ = -1;
            private int nChannels = -1, nTimePoints = -1;
            private Map<Integer, Map<Integer, RandomAccessibleInterval<T>>> ctToRAI = new HashMap<>();
            RandomAccessibleInterval<T> model;
            T pixelInstance;

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

            public OMETiffPyramidizerExporter create() throws Exception {
                if ((filePath == null)||(filePath.trim().equals(""))) {
                    throw new IOException("Invalid path file");
                }

                if (new File(filePath).exists()) {
                    throw new IOException("Path "+filePath+" already exists");
                }

                WriterOptions wOpts = new WriterOptions(this);
                return new OMETiffPyramidizerExporter(data.ctToRAI, metaData.omeMeta, 0,wOpts);
            }

        }
    }


}
