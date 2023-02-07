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
package ch.epfl.biop;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.*;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.formats.meta.IPyramidStore;
import loci.formats.out.PyramidOMETiffWriter;
import loci.formats.tiff.IFD;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.units.unit.Unit;
import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.enums.PixelType;
import ome.xml.model.primitives.Color;
import ome.xml.model.primitives.PositiveInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;

public class ImagePlusToOMETiff {

    private static final Logger logger = LoggerFactory.getLogger(ImagePlusToOMETiff.class);

    /**
     * Static utility class to convert an ImagePlus as a OME-TIFF multiresolution file
     *
     * Unsupported for now :
     * - RGB images
     * - Tiled images
     *
     * @param image image plus to save
     * @param outFile the file to save
     * @param resolutions number of resolution levels to generate (minimum = 1)
     * @param scale scaling factor between each resolution level
     * @param compression compression type, see OME allowed compression
     * @throws Exception exception thrown during writing
     */
    // Inspired from https://github.com/dgault/bio-formats-examples/blob/6cdb11e8c64566611b18f384b3a257dab5037e90/src/main/macros/jython/PyramidConversion.py
    // And https://github.com/qupath/qupath/blob/430d6212e641f677dc9a411cf8033fbbe4da2fd6/qupath-extension-bioformats/src/main/java/qupath/lib/images/writers/ome/OMEPyramidWriter.java
    public static void writeToOMETiff(ImagePlus image, File outFile, int resolutions, int scale, String compression) throws Exception{

        // Copy metadata from ImagePlus:
        IMetadata omeMeta = MetadataTools.createOMEXMLMetadata();

        boolean isLittleEndian = false;
        boolean isRGB = false;
        boolean isInterleaved = false;
        int nChannels = image.getNChannels();

        int series = 0;
        omeMeta.setImageID("Image:"+series, series);
        omeMeta.setPixelsID("Pixels:"+series, series);
        if (image.getTitle() != null)
            omeMeta.setImageName(image.getTitle(), series);

        omeMeta.setPixelsDimensionOrder(DimensionOrder.XYCZT, series);
        switch (image.getType()) {
            case ImagePlus.GRAY8:
                omeMeta.setPixelsType(PixelType.UINT8, series);
                break;
            case ImagePlus.GRAY16:
                isLittleEndian = false;
                omeMeta.setPixelsType(PixelType.UINT16, series);
                break;
            case ImagePlus.GRAY32:
                isLittleEndian = false;
                omeMeta.setPixelsType(PixelType.FLOAT, series);
                break;
            case ImagePlus.COLOR_RGB:
                nChannels = 3;
                isInterleaved = true;
                isRGB = true;
                omeMeta.setPixelsType(PixelType.UINT8, series);
                break;
            default:
                throw new UnsupportedOperationException("Cannot convert image of type " + image.getType() + " into a valid OME PixelType");
        }

        omeMeta.setPixelsBigEndian(!isLittleEndian, 0);

        int width = image.getWidth();
        int height = image.getHeight();

        int sizeZ = image.getNSlices();
        int sizeT = image.getNFrames();
        int sizeC = image.getNChannels();

        // Set resolutions
        omeMeta.setPixelsSizeX(new PositiveInteger(width), series);
        omeMeta.setPixelsSizeY(new PositiveInteger(height), series);
        omeMeta.setPixelsSizeZ(new PositiveInteger(sizeZ), series);
        omeMeta.setPixelsSizeT(new PositiveInteger(sizeT), series);

        // Set channel colors
        omeMeta.setPixelsSizeC(new PositiveInteger(nChannels), series);
        if (isRGB) {
            omeMeta.setChannelID("Channel:0", series, 0);
            omeMeta.setPixelsInterleaved(isInterleaved, series);
            omeMeta.setChannelSamplesPerPixel(new PositiveInteger(3), series, 0); //nSamples = 3; // TODO : check!
        } else {
            omeMeta.setChannelSamplesPerPixel(new PositiveInteger(1), series, 0);
            omeMeta.setPixelsInterleaved(isInterleaved, series);
            for (int c = 0; c < nChannels; c++) {
                omeMeta.setChannelID("Channel:0:" + c, series, c);
                // omeMeta.setChannelSamplesPerPixel(new PositiveInteger(1), series, c);
                LUT channelLUT = image.getLuts()[c];
                int colorRed = channelLUT.getRed(255);
                int colorGreen = channelLUT.getGreen(255);
                int colorBlue = channelLUT.getBlue(255);
                int colorAlpha = channelLUT.getAlpha(255);
                omeMeta.setChannelColor(new Color(colorRed, colorGreen, colorBlue, colorAlpha), series, c);
                omeMeta.setChannelName("Channel_"+c, series, c);
            }
        }

        // Set physical units, if we have them

        if (image.getCalibration()!=null) {
            Calibration cal = image.getCalibration();
            Unit<Length> unit = getUnitFromCalibration(cal);
            omeMeta.setPixelsPhysicalSizeX(new Length(Math.abs(cal.pixelWidth), unit), series);
            omeMeta.setPixelsPhysicalSizeY(new Length(Math.abs(cal.pixelHeight), unit), series);
            omeMeta.setPixelsPhysicalSizeZ(new Length(Math.abs(cal.pixelDepth), unit), series);
            // set Origin in XYZ
            // TODO : check if enough or other planes need to be set ?
            omeMeta.setPlanePositionX(new Length(Math.abs(cal.xOrigin*cal.pixelWidth), unit),0,0);
            omeMeta.setPlanePositionY(new Length(Math.abs(cal.yOrigin*cal.pixelHeight), unit),0,0);
            omeMeta.setPlanePositionZ(new Length(Math.abs(cal.zOrigin*cal.pixelDepth), unit),0,0);
        }

        // setup resolutions
        for (int i= 0;i<resolutions-1;i++) {
            double divScale = Math.pow(scale, i + 1);
            ((IPyramidStore)omeMeta).setResolutionSizeX(new PositiveInteger((int)(width / divScale)),series, i + 1);
            ((IPyramidStore)omeMeta).setResolutionSizeY(new PositiveInteger((int)(height / divScale)),series, i + 1);
        }

        // setup writer
        PyramidOMETiffWriter writer = new PyramidOMETiffWriter();

        writer.setWriteSequentially(true); // Setting this to false can be problematic!

        writer.setMetadataRetrieve(omeMeta);
        writer.setBigTiff(true);
        writer.setId(outFile.getAbsolutePath());
        writer.setSeries(0);
        writer.setCompression(compression);
        writer.setInterleaved(isInterleaved);

        // generate downsampled resolutions and write to output
        for (int r = 0; r < resolutions; r++) {
            logger.debug("Saving resolution size " + r);
            writer.setResolution(r);

            for (int t=0;t<image.getNFrames();t++) {
                for (int z=0;z<image.getNSlices();z++) {
                    for (int c=0;c<image.getNChannels();c++) {

                        ImageProcessor processor;

                        if (image.getStack()==null) {
                            processor = image.getProcessor();
                        } else {
                            processor = image.getStack().getProcessor(image.getStackIndex(c+1, z+1, t+1));
                        }

                        if (r!=0) {
                            Integer x = ((IPyramidStore)omeMeta).getResolutionSizeX(0, r).getValue();
                            Integer y = ((IPyramidStore)omeMeta).getResolutionSizeY(0, r).getValue();
                            processor.setInterpolationMethod(ImageProcessor.BILINEAR);
                            processor = processor.resize(x,y);
                        }

                        int plane = t * sizeZ * sizeC + z * sizeC + c;

                        // You'd better keep these three lines to avoid an annoying Windows related issue
                        IFD ifd = new IFD();
                        ifd.putIFDValue(IFD.TILE_WIDTH, processor.getWidth());
                        ifd.putIFDValue(IFD.TILE_LENGTH, processor.getHeight());

                        writer.saveBytes(plane, processorToBytes(processor, processor.getWidth()*processor.getHeight()), ifd);
                    }
                }
            }
        }
        writer.close();
    }

    public static Unit<Length> getUnitFromCalibration(Calibration cal) {
        switch (cal.getUnit()) {
            case "um":
            case "\u03BCm":
            case "\u03B5m":
            case "µm":
            case "micrometer":
                return UNITS.MICROMETER;
            case "mm":
            case "millimeter":
                return UNITS.MILLIMETER;
            case "cm":
            case "centimeter":
                return UNITS.CENTIMETER;
            case "m":
            case "meter":
                return UNITS.METRE;
            default:
                return UNITS.REFERENCEFRAME;
        }
    }

    private static byte[] processorToBytes(ImageProcessor processor, int nPixels) {
        ByteBuffer byteBuf;
        switch (processor.getBitDepth()) {
            case 8:
                return (byte[]) processor.getPixels();
            case 16:
                //https://stackoverflow.com/questions/10804852/how-to-convert-short-array-to-byte-array
                // Slow...
                byteBuf = ByteBuffer.allocate(2*nPixels);
                short[] pixels_short = (short[]) processor.getPixels();
                for (short v : pixels_short) byteBuf.putShort(v);
                return byteBuf.array();
            case 32:
                byteBuf = ByteBuffer.allocate(4*nPixels);
                float[] pixels_float = (float[]) processor.getPixels();
                for (float v : pixels_float) byteBuf.putFloat(v);
                return byteBuf.array();
            case 24:
                byteBuf = ByteBuffer.allocate(3*nPixels);
                int[] pixels_rgb = (int[]) processor.getPixels();
                for (int v : pixels_rgb) {
                    byteBuf.put((byte)((v >> 16) & 0xFF));
                    byteBuf.put((byte)((v >> 8) & 0xFF));
                    byteBuf.put((byte)(v & 0xFF));
                }
                return byteBuf.array();
            default:
                throw new UnsupportedOperationException("Unhandled bit depth: "+processor.getBitDepth());
        }
    }

}
