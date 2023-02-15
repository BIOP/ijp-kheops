/*-
 * #%L
 * IJ2 commands that use bio-formats to create pyramidal ome.tiff
 * %%
 * Copyright (C) 2018 - 2023 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP)
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

import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.bdv.img.bioformats.BioFormatsHelper;
import ch.epfl.biop.kheops.KheopsHelper;
import ch.epfl.biop.kheops.ometiff.OMETiffExporter;
import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import loci.common.DebugTools;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.units.unit.Unit;
import org.apache.commons.io.FilenameUtils;
import org.scijava.task.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

public class ImagePlusToOMETiff {

    private static final Logger logger = LoggerFactory.getLogger(ImagePlusToOMETiff.class);

    /**
     * TODO : make a builder pattern  and use this class in KheopsFromImagePlusCommand
     *
     * Static utility class to convert an ImagePlus as a OME-TIFF multiresolution file
     *
     * Unsupported for now :
     * - RGB images
     * - Tiled images
     *
     * @param image image plus to save
     * @param outFile the file to save
     * @param compression compression type, see OME allowed compression
     * @throws Exception exception thrown during writing
     *
     */
    // Inspired from https://github.com/dgault/bio-formats-examples/blob/6cdb11e8c64566611b18f384b3a257dab5037e90/src/main/macros/jython/PyramidConversion.py
    // And https://github.com/qupath/qupath/blob/430d6212e641f677dc9a411cf8033fbbe4da2fd6/qupath-extension-bioformats/src/main/java/qupath/lib/images/writers/ome/OMEPyramidWriter.java
    public static void writeToOMETiff(ImagePlus image, File outFile, String compression, TaskService taskService) throws Exception{

        Instant start = Instant.now();
        String imageTitle = image.getTitle();

        //--------------------
        int tileSize = 512;
        int nThreads = Math.max(1,Runtime.getRuntime().availableProcessors()-1);

        File output_dir = outFile.getParentFile();
        //if (!output_dir.exists()) output_dir.mkdirs();

        DebugTools.enableLogging("WARN");

        int numberOfBlocksComputedInAdvance = 64;

        KheopsHelper.SourcesInfo sourcesInfo =
                KheopsHelper
                        .getSourcesFromImage(image, numberOfBlocksComputedInAdvance,nThreads);

        Set<String> paths = new HashSet<>();

        String fileNameWithOutExt = FilenameUtils.removeExtension(imageTitle);

        boolean appendSuffix = false;
        int counter = 0;
        if (paths.contains(fileNameWithOutExt)) {
            fileNameWithOutExt += "_s" + 0;
        }
        while (paths.contains(fileNameWithOutExt)) {
            if (appendSuffix) {
                fileNameWithOutExt = fileNameWithOutExt.substring(0, fileNameWithOutExt.length() - 2) + "_" + counter;
            } else {
                fileNameWithOutExt += "_" + counter;
            }
            appendSuffix = true;
            counter++;
        }
        paths.add(fileNameWithOutExt);

        SourceAndConverter[] sources = sourcesInfo.idToSources.get(0).toArray(new SourceAndConverter[0]);

        File output_path = new File(output_dir, fileNameWithOutExt+".ome.tiff");

        if (output_path.exists()) {
            IJ.log("Error: file " + output_path.getAbsolutePath() + " already exists. Skipped!");
        } else {

            int sizeFullResolution = (int) Math.min(sources[0].getSpimSource().getSource(0, 0).max(0), sources[0].getSpimSource().getSource(0, 0).max(1));

            int nResolutions = 1;

            while (sizeFullResolution > tileSize) {
                sizeFullResolution /= 2;
                nResolutions++;
            }

            try {

                Unit<Length> u;
                if (image.getCalibration()==null) {
                    logger.warn("No calibration!");
                    u = UNITS.REFERENCEFRAME;
                    image.setCalibration(new Calibration());
                } else {
                    u = BioFormatsHelper.getUnitFromString(image.getCalibration().getUnit());
                    if (u==null) {
                        if (image.getCalibration().getUnit().equals("um") || image.getCalibration().getUnit().equals("µm")) {
                            u = UNITS.MICROMETER;
                        } else {
                            u = UNITS.REFERENCEFRAME;
                        }
                    }
                }


                OMETiffExporter.OMETiffExporterBuilder.MetaData.MetaDataBuilder builder = OMETiffExporter.builder()
                        .put(sources)
                        .defineMetaData(image.getTitle())
                        .putMetadataFromSources(sources, u)
                        .pixelsTimeIncrementInS(image.getCalibration().frameInterval);
                //.applyOnMeta(meta -> {
                //    return meta;
                //});

                OMETiffExporter.OMETiffExporterBuilder.WriterOptions.WriterOptionsBuilder exportBuilder = builder.defineWriteOptions()
                        .maxTilesInQueue(numberOfBlocksComputedInAdvance)
                        .compression(compression)
                        .compressTemporaryFiles(false)
                        .nThreads(nThreads)
                        .downsample(2)
                        .nResolutionLevels(nResolutions)
                        //.rangeT(subset_frames)
                        //.rangeC(subset_channels)
                        //.rangeZ(subset_slices)
                        .savePath(output_path.getAbsolutePath())
                        .tileSize(tileSize, tileSize);

                if (taskService!=null) {
                    exportBuilder.monitor(taskService);
                }

                // Do it now
                exportBuilder.create().export();

            } catch (Exception e) {
                IJ.log("Error with " + output_path + " export: "+e.getMessage());

            }
        }

        sourcesInfo.readerPool.shutDown(reader -> {
            try {
                reader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        // CODE HERE
        Instant finish = Instant.now();
        long timeElapsed = Duration.between(start, finish).toMillis();
        logger.info(imageTitle+"\t OME TIFF conversion (Kheops) \t Run time=\t"+(timeElapsed/1000)+"\t s"+"\t parallel = \t "+false+"\t nProcessors = \t"+nThreads);
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

}
