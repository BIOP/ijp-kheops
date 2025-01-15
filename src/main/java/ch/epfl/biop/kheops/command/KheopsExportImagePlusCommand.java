/*-
 * #%L
 * IJ2 commands that use bio-formats to create pyramidal ome.tiff
 * %%
 * Copyright (C) 2018 - 2024 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP)
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
package ch.epfl.biop.kheops.command;

import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.kheops.KheopsHelper;
import ch.epfl.biop.kheops.ometiff.OMETiffExporter;
import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import loci.common.DebugTools;
import ome.units.quantity.Length;
import ome.units.unit.Unit;
import org.apache.commons.io.FilenameUtils;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.task.TaskService;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

@SuppressWarnings("CanBeFinal")
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Kheops>Kheops - Convert Image to Pyramidal OME TIFF")
public class KheopsExportImagePlusCommand implements Command {

    @Parameter(label = "Image")
    ImagePlus image;

    @Parameter(label= "Output folder (optional)", style = "directory")
    File output_dir;

    @Parameter(label="Compression type", choices = {"LZW", "Uncompressed", "JPEG-2000", "JPEG-2000 Lossy", "JPEG"})
    String compression = "LZW";

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false, required = false)
    String message = "<html><b>Subset in CTZ. Leave fields blank to export all.<br></b>" +
            "You can use commas or colons to separate ranges. eg. '1:2:10' or '1,3,5,8'. '-1' is the last index.</html>";

    @Parameter( label = "Channels subset:", required = false )
    String subset_channels = "";

    @Parameter( label = "Slices subset:", required = false )
    String subset_slices = "";

    @Parameter( label = "Timepoints subset:", required = false )
    String subset_frames = "";

    @Parameter(label="Compress temporary files (LZW)")
    boolean compress_temp_files = false;

    public static Consumer<String> logger = IJ::log;

    @Parameter
    TaskService taskService;

    @Override
    public void run() {

        Instant start = Instant.now();
        String imageTitle = image.getTitle();

        //--------------------
        int tileSize = 512;
        int nThreads = Math.max(1,Runtime.getRuntime().availableProcessors()-1);

        if (!output_dir.exists()) output_dir.mkdirs();

        DebugTools.enableLogging("WARN");

        int numberOfBlocksComputedInAdvance = 64;

        KheopsHelper.SourcesInfo sourcesInfo =
                KheopsHelper
                        .getSourcesFromImage(image, numberOfBlocksComputedInAdvance, nThreads);

        String fileNameWithOutExt = FilenameUtils.removeExtension(imageTitle);

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

                    if (image.getCalibration() == null) image.setCalibration(new Calibration());

                    Unit<Length> u = KheopsHelper.getUnitFromCalibration(image.getCalibration());

                    OMETiffExporter.OMETiffExporterBuilder.MetaData.MetaDataBuilder builder = OMETiffExporter.builder()
                            .put(sources)
                            .defineMetaData(image.getTitle())
                            .putMetadataFromSources(sources, u)
                            .pixelsTimeIncrementInS(image.getCalibration().frameInterval);

                    OMETiffExporter exporter = builder.defineWriteOptions()
                            .maxTilesInQueue(numberOfBlocksComputedInAdvance)
                            .compression(compression)
                            .compressTemporaryFiles(compress_temp_files)
                            .nThreads(nThreads)
                            .downsample(2)
                            .nResolutionLevels(nResolutions)
                            .rangeT(subset_frames)
                            .rangeC(subset_channels)
                            .rangeZ(subset_slices)
                            .monitor(taskService)
                            .savePath(output_path.getAbsolutePath())
                            .tileSize(tileSize, tileSize).create();

                    exporter.export();


                } catch (Exception e) {
                    IJ.log("Error with " + output_path + " export: "+e.getMessage());
                }
            }

        // CODE HERE
        Instant finish = Instant.now();
        long timeElapsed = Duration.between(start, finish).toMillis();
        logger.accept(imageTitle+"\t OME TIFF conversion (Kheops) \t Run time=\t"+(timeElapsed/1000)+"\t s"+"\t parallel = \t "+false+"\t nProcessors = \t"+nThreads);
    }

}
