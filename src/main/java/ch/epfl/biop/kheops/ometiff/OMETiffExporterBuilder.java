package ch.epfl.biop.kheops.ometiff;

import bdv.viewer.SourceAndConverter;
import bdv.viewer.Source;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.display.ColorConverter;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.units.quantity.Time;
import ome.units.unit.Unit;
import ome.xml.meta.MetadataConverter;
import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.enums.PixelType;
import ome.xml.model.primitives.Color;
import ome.xml.model.primitives.PositiveInteger;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class OMETiffExporterBuilder<T> {

    IMetadata omeMeta = MetadataTools.createOMEXMLMetadata();

    private int nPixelX = -1, nPixelY = -1, nPixelZ = -1;
    private int nChannels = -1, nTimePoints = -1;

    Map<Integer, Map<Integer, RandomAccessibleInterval<T>>> ctToRAI = new HashMap<>();

    RandomAccessibleInterval<T> model;

    final int series = 0; // No support of multi series OME TIFF

    boolean isLittleEndian = false;
    boolean isRGB = false;
    volatile boolean isInterleaved = false;
    boolean isFloat = false;
    int bytesPerPixel;

    WriterSettings writerSettings;

    public static OMETiffExporterBuilder builder() {
        OMETiffExporterBuilder builder = new OMETiffExporterBuilder();
        builder.omeMeta.setImageID("Image:" + builder.series, builder.series);
        builder.omeMeta.setPixelsID("Pixels:" + builder.series, builder.series);
        builder.omeMeta.setImageName("name", builder.series);
        return builder;
    }

    public OMETiffExporterBuilder<T> applyOnMeta(Function<IMetadata, IMetadata> f) {
        omeMeta = f.apply(this.omeMeta);
        return this;
    }
    public OMETiffExporterBuilder<T> put(int channel, Source<T> source) throws UnsupportedOperationException {
        int t = 0;
        while (source.isPresent(t)) {
            put3DRAI(channel, t, source.getSource(t,0));
        }
        return this;
    }

    public OMETiffExporterBuilder<T> put(Source<T> source) throws UnsupportedOperationException {
        return put(0, source);
    }

    public OMETiffExporterBuilder<T> put(SourceAndConverter<T>[] sources) throws UnsupportedOperationException {
        for (int c = 0; c<sources.length;c++) {
            put(c, sources[c]);
        }
        return this;
    }

    public OMETiffExporterBuilder<T> put(int c, SourceAndConverter<T> source) throws UnsupportedOperationException {
        put(c, source.getSpimSource());
        return this;
    }

    public OMETiffExporterBuilder<T> put3DRAI(int channel, int timepoint, RandomAccessibleInterval<T> rai) throws UnsupportedOperationException {
        validate(channel, timepoint);
        validate(channel, rai);
        ctToRAI.get(channel).put(timepoint, rai);
        return this;
    }

    public OMETiffExporterBuilder<T> put3DRAI(RandomAccessibleInterval<T> rai) throws UnsupportedOperationException {
        put3DRAI(0,0,rai);
        return this;
    }

    public OMETiffExporterBuilder<T> imageName(String imageName) {
        omeMeta.setImageName(imageName, series);
        return this;
    }

    public OMETiffExporterBuilder<T> channelName(int channel, String channelName) {
        omeMeta.setChannelID("Channel:0:" + channel, series, channel);
        omeMeta.setChannelName(channelName, series, channel);
        return this;
    }

    public OMETiffExporterBuilder<T> channelColor(int channel, int r, int g, int b, int a) {
        omeMeta.setChannelColor(new Color(r, g, b, a), series, channel);
        return this;
    }

    public OMETiffExporterBuilder<T> planePosition(Length originX, Length originY, Length originZ, int planeIndex) {
        omeMeta.setPlanePositionX(originX, series, planeIndex);
        omeMeta.setPlanePositionY(originY, series, planeIndex);
        omeMeta.setPlanePositionZ(originZ, series, planeIndex);
        return this;
    }

    public OMETiffExporterBuilder<T> planePositionMicrometer(double originX, double originY, double originZ, int planeIndex) {
        return planePosition(new Length(originX, UNITS.MICROMETER),
                new Length(originY, UNITS.MICROMETER),
                new Length(originZ, UNITS.MICROMETER),
                planeIndex
                );
    }

    public OMETiffExporterBuilder<T> planePositionMillimeter(double originX, double originY, double originZ, int planeIndex) {
        return planePosition(new Length(originX, UNITS.MILLIMETER),
                new Length(originY, UNITS.MILLIMETER),
                new Length(originZ, UNITS.MILLIMETER),
                planeIndex
        );
    }

    public OMETiffExporterBuilder<T> pixelsTimeIncrementInS(double timeInS) {
        omeMeta.setPixelsTimeIncrement(new Time(timeInS, UNITS.SECOND), series);
        return this;
    }

    public OMETiffExporterBuilder<T> voxelPhysicalSize(Length physicalSizeX, Length physicalSizeY, Length physicalSizeZ) {
        omeMeta.setPixelsPhysicalSizeX(physicalSizeX,series);
        omeMeta.setPixelsPhysicalSizeX(physicalSizeY,series);
        omeMeta.setPixelsPhysicalSizeZ(physicalSizeZ, series);
        return this;
    }

    public OMETiffExporterBuilder<T> voxelPhysicalSizeMicrometer(double physicalSizeXInMicrometer, double physicalSizeYInMicrometer, double physicalSizeZInMicrometer) {
        return voxelPhysicalSize(new Length(physicalSizeXInMicrometer, UNITS.MICROMETER),
                new Length(physicalSizeYInMicrometer, UNITS.MICROMETER),
                new Length(physicalSizeZInMicrometer, UNITS.MICROMETER));
    }

    public OMETiffExporterBuilder<T> voxelPhysicalSizeMillimeter(double physicalSizeXInMillimeter, double physicalSizeYInMillimeter, double physicalSizeZInMillimeter) {
        return voxelPhysicalSize(new Length(physicalSizeXInMillimeter, UNITS.MILLIMETER),
                new Length(physicalSizeYInMillimeter, UNITS.MILLIMETER),
                new Length(physicalSizeZInMillimeter, UNITS.MILLIMETER));
    }

    public OMETiffExporterBuilder<T> writeSettings(WriterSettings writerSettings) {
        this.writerSettings = writerSettings;
        return this;
    }

    // --- Non public methods - validation

    public OMETiffPyramidizerExporterNew get() throws Exception {
        // Set timepoint
        // Set channel, check RGB = single channel

        omeMeta.setPixelsSizeT(new PositiveInteger(nTimePoints), series);
        if ((isRGB)&&(nChannels>1)) {
            throw new UnsupportedOperationException("Multi channels RGB images not supported");
        }
        omeMeta.setPixelsSizeC(new PositiveInteger(isRGB ? 3 : nChannels), series);

        // Fill with zeros ?

        if (nChannels<1) {
            throw new UnsupportedOperationException("No channel found, nChannels = "+nChannels+". You probably did not specify any data.");
        }

        if (nTimePoints<1) {
            throw new UnsupportedOperationException("No timepoint found, nTimepoints = "+nTimePoints+". You probably did not specify any data.");
        }

        if (writerSettings == null) {
            throw new UnsupportedOperationException("You did not specify any writer settings");
        }

        // Check if no data is missing
        for (int c = 0; c<nChannels; c++) {
            for (int t = 0; t<nTimePoints; t++) {
                if (!ctToRAI.containsKey(c)) throw new UnsupportedOperationException("Channel "+c+" missing. You probably forgot to specify the data for this channel.");
                if (!ctToRAI.get(c).containsKey(t)) throw new UnsupportedOperationException("Timepoint "+t+" missing for channel "+c+". You probably forgot to specify the data for this channel and timepoint.");
            }
        }

        // Check if all the data is there. FillWithZero if not ?
        return new OMETiffPyramidizerExporterNew(ctToRAI, omeMeta, series,  writerSettings);
    }

    public OMETiffExporterBuilder<T> copyMetaData(IMetadata meta) {
        MetadataConverter.convertMetadata(meta, meta);
        return this;
    }

    public OMETiffExporterBuilder<T> copyChannelMetaData(IMetadata meta, int imageSrc, int channelSrc, int channelDest, boolean copyID) {
        MetadataConverter.convertChannels(meta, imageSrc, channelSrc, omeMeta, series, channelDest, copyID);
        return this;
    }

    public OMETiffExporterBuilder<T> metaDataFromSourceAndConvertersMicrometer(SourceAndConverter[] sources) {
        return metaDataFromSourceAndConverters(sources, UNITS.MICROMETER);
    }

    public OMETiffExporterBuilder<T> metaDataFromSourceAndConvertersMillimeter(SourceAndConverter[] sources) {
        return metaDataFromSourceAndConverters(sources, UNITS.MILLIMETER);
    }

    public OMETiffExporterBuilder<T> metaDataFromSourceAndConverters(SourceAndConverter[] sources, Unit<Length> unit) {
        for (int c=0; c<sources.length;c++) {
            metaDataFromSourceAndConverter(c, sources[c], unit);
        }
        return this;
    }

    public OMETiffExporterBuilder<T> metaDataFromSourceAndConverter(int c, SourceAndConverter source, Unit<Length> unit) {
        AffineTransform3D mat = new AffineTransform3D();
        source.getSpimSource().getSourceTransform(0, 0, mat);

        // Voxel size
        double[] m = mat.getRowPackedCopy();
        double[] voxelSizes = new double[3];

        for (int d = 0; d < 3; ++d) {
            voxelSizes[d] = Math.sqrt(m[d] * m[d] + m[d + 4] * m[d + 4] + m[d + 8] *
                    m[d + 8]);
        }
        voxelPhysicalSize(new Length(voxelSizes[0], unit), new Length(voxelSizes[1], unit), new Length(voxelSizes[2], unit));

        // Color
        if (source.getConverter() instanceof ColorConverter) {
            int colorCode = ((ColorConverter)source.getConverter()).getColor().get();
            int colorRed = ARGBType.red(colorCode);
            int colorGreen = ARGBType.green(colorCode);
            int colorBlue = ARGBType.blue(colorCode);
            int colorAlpha = ARGBType.alpha(colorCode);
            omeMeta.setChannelColor(new Color(colorRed, colorGreen, colorBlue,
                    colorAlpha), series, c);
        }
        return this;
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

    private void validate(int channel, RandomAccessibleInterval<T> rai) throws UnsupportedOperationException {
        if (rai.numDimensions()!=3) throw new UnsupportedOperationException("All random accessible intervals should be 3D");
        if (nPixelX == -1) { // First RAI given, let's take the sizes
            if (rai.dimension(0)>Integer.MAX_VALUE) throw new UnsupportedOperationException("Image too big along X ("+rai.dimension(0)+">"+Integer.MAX_VALUE+")");
            if (rai.dimension(1)>Integer.MAX_VALUE) throw new UnsupportedOperationException("Image too big along Y ("+rai.dimension(1)+">"+Integer.MAX_VALUE+")");
            if (rai.dimension(2)>Integer.MAX_VALUE) throw new UnsupportedOperationException("Image too big along Z ("+rai.dimension(2)+">"+Integer.MAX_VALUE+")");
            nPixelX = (int) rai.dimension(0);
            nPixelY = (int) rai.dimension(1);
            nPixelZ = (int) rai.dimension(2);
            model = rai;
            setOmeMetaFromModelRAI(channel, model);
        }
        if (rai.dimension(0)!=nPixelX) throw new UnsupportedOperationException("All random accessible intervals should have the same dimension (size X: "+nPixelX+" != "+rai.dimension(0));
        if (rai.dimension(1)!=nPixelY) throw new UnsupportedOperationException("All random accessible intervals should have the same dimension (size Y: "+nPixelY+" != "+rai.dimension(1));
        if (rai.dimension(2)!=nPixelZ) throw new UnsupportedOperationException("All random accessible intervals should have the same dimension (size Z: "+nPixelZ+" != "+rai.dimension(2));
        // Can't test the type...
    }

    private void setOmeMetaFromModelRAI(int channel, RandomAccessibleInterval<T> rai) throws UnsupportedOperationException{
        T pixelType = rai.getAt(0,0,0);
        if (pixelType instanceof UnsignedShortType) {
            omeMeta.setPixelsType(PixelType.UINT16, series);
            bytesPerPixel = 2;
            isInterleaved = false;
            omeMeta.setPixelsInterleaved(false, series);
            omeMeta.setPixelsDimensionOrder(DimensionOrder.XYZCT, series);
        }
        else if (pixelType instanceof UnsignedByteType) {
            omeMeta.setPixelsType(PixelType.UINT8, series);
            bytesPerPixel = 1;
            isInterleaved = false;
            omeMeta.setPixelsInterleaved(false, series);
            omeMeta.setPixelsDimensionOrder(DimensionOrder.XYZCT, series);
        }
        else if (pixelType instanceof FloatType) {
            omeMeta.setPixelsType(PixelType.FLOAT, series);
            bytesPerPixel = 4;
            isFloat = true;
            isInterleaved = false;
            omeMeta.setPixelsInterleaved(false, series);
            omeMeta.setPixelsDimensionOrder(DimensionOrder.XYZCT, series);
        }
        else if (pixelType instanceof ARGBType) {
            isInterleaved = true;
            isRGB = true;
            bytesPerPixel = 1;
            omeMeta.setPixelsType(PixelType.UINT8, series);
            omeMeta.setPixelsDimensionOrder(DimensionOrder.XYCZT, series);
            omeMeta.setPixelsInterleaved(true, series);
        }
        else {
            throw new UnsupportedOperationException("Unhandled pixel type class: " +
                    pixelType.getClass().getName());
        }

        omeMeta.setPixelsBigEndian(!isLittleEndian, series);

        // Set image dimension
        omeMeta.setPixelsSizeX(new PositiveInteger((int) model.dimension(0)), series);
        omeMeta.setPixelsSizeY(new PositiveInteger((int) model.dimension(1)), series);
        omeMeta.setPixelsSizeZ(new PositiveInteger((int) model.dimension(2)), series);
        if (isRGB) {
            omeMeta.setChannelSamplesPerPixel(new PositiveInteger(3), series, channel);
        } else {
            omeMeta.setChannelSamplesPerPixel(new PositiveInteger(1), series, channel);
        }
    }

}
