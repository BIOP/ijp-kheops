/*-
 * #%L
 * IJ2 command make use of bioformats convert to create pyramidal ome.tiff
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
package ch.epfl.biop.kheops.command;

import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.kheops.KheopsHelper;
import ch.epfl.biop.kheops.ometiff.OMETiffPyramidizerExporter;
import ij.IJ;
import loci.common.DebugTools;
import org.apache.commons.io.FilenameUtils;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.task.TaskService;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Creates a pyramidal OME TIFF from a single file
 */

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Kheops>Kheops - Convert File to Pyramidal OME")
public class KheopsCommand implements Command {



    @Parameter(label = "Select an input file (required)", style="open")
    File input_path;

    @Parameter( label = "Selected Channels. Leave blank for all", required = false )
    String range_channels = "";

    @Parameter( label = "Selected Slices. Leave blank for all", required = false )
    String range_slices = "";

    @Parameter( label = "Selected Timepoints. Leave blank for all", required = false )
    String range_frames = "";

    @Parameter(label= "Specify an output folder (optional)", style = "directory", required=false, persist=false)
    File output_dir;

    @Parameter(label="Compression type", choices = {"LZW", "Uncompressed", "JPEG-2000", "JPEG-2000 Lossy", "JPEG"})
    String compression = "LZW";

    @Parameter(label="Compress temporary files (save space on drive during conversion)")
    boolean compress_temp_files = false;

    @Parameter(label="Override voxel sizes")
    boolean override_voxel_size;

    @Parameter(label="Voxel size in micrometer (XY)", style="format:#.000")
    double vox_size_xy;

    @Parameter(label="Voxel Z size in micrometer (Z)", style="format:#.000")
    double vox_size_z;

    Set<String> paths = new HashSet<>();
    public static Consumer<String> logger = (str) -> IJ.log(str);

    @Parameter
    TaskService taskService;

    @Override
    public void run() {

        Instant start = Instant.now();
        String fileName = input_path.getName();

        //--------------------
        int tileSize = 512;
        int nThreads = Math.max(1,Runtime.getRuntime().availableProcessors()-1);

        File parent_dir = new File(input_path.getParent());

        if ((output_dir == null) || (output_dir.toString().equals(""))) {
            output_dir = parent_dir;
        } else {
            output_dir.mkdirs();
        }

        DebugTools.enableLogging("OFF");

        int numberOfBlocksComputedInAdvance = 64;

        KheopsHelper.SourcesInfo sourcesInfo =
                KheopsHelper.getSourcesFromFile(input_path.getAbsolutePath(), tileSize, tileSize, numberOfBlocksComputedInAdvance,
                        nThreads);

        int nSeries = sourcesInfo.idToSources.keySet().size();

        boolean process_series_in_parallel = true;

        if (nSeries==1) process_series_in_parallel = false;

        IntStream idStream =  IntStream.range(0,nSeries);

        if (process_series_in_parallel) idStream.parallel();

        final boolean parallelProcess = process_series_in_parallel;

        idStream.forEach(iSeries -> {

            String fileNameWithOutExt = FilenameUtils.removeExtension(fileName);
            SourceAndConverter[] sources = sourcesInfo.idToSources.get(iSeries).toArray(new SourceAndConverter[0]);

            if (nSeries>1) {
                if (sourcesInfo.idToNames.containsKey(iSeries)) {
                    fileNameWithOutExt += "_" + sourcesInfo.idToNames.get(iSeries);
                } else {
                    fileNameWithOutExt += "_" + iSeries;
                }
            }

            // avoid duplicate names
            synchronized (paths) {
                boolean appendSuffix = false;
                int counter = 0;
                if (paths.contains(fileNameWithOutExt)) {
                    fileNameWithOutExt+="_s"+iSeries;
                }
                while (paths.contains(fileNameWithOutExt)) {
                    if (appendSuffix) {
                        fileNameWithOutExt = fileNameWithOutExt.substring(0,fileNameWithOutExt.length()-2)+"_"+counter;
                    } else {
                        fileNameWithOutExt += "_"+counter;
                    }
                    appendSuffix = true;
                    counter++;
                }
                paths.add(fileNameWithOutExt);
            }

            fileNameWithOutExt+=".ome.tiff";

            File output_path = new File(output_dir, fileNameWithOutExt);

            int sizeFullResolution = (int) Math.min(sources[0].getSpimSource().getSource(0,0).max(0),sources[0].getSpimSource().getSource(0,0).max(1));

            int nResolutions = 1;

            while (sizeFullResolution>tileSize) {
                sizeFullResolution/=2;
                nResolutions++;
            }

            try {

                OMETiffPyramidizerExporter.Builder builder = OMETiffPyramidizerExporter.builder()
                        .compression(compression)
                        .compressTemporaryFiles(compress_temp_files)
                        .maxTilesInQueue(numberOfBlocksComputedInAdvance)
                        .nThreads(parallelProcess?0:nThreads)
                        .downsample(2)
                        .nResolutionLevels(nResolutions)
                        .micrometer()
                        .rangeT(range_frames)
                        .rangeC(range_channels)
                        .rangeZ(range_slices)
                        .savePath(output_path.getAbsolutePath())
                        .monitor(taskService)
                        .tileSize(tileSize, tileSize);

                if (override_voxel_size) {
                    builder.setPixelSize(this.vox_size_xy, this.vox_size_xy, this.vox_size_z);
                }

                builder.create(sources).export();

            } catch (Exception e) {
                IJ.log("Error with "+fileNameWithOutExt+" export.");
                e.printStackTrace();
            }

        });

        // CODE HERE
        Instant finish = Instant.now();
        long timeElapsed = Duration.between(start, finish).toMillis();
        logger.accept(input_path.getName()+"\t OME TIFF conversion (Kheops) \t Run time=\t"+(timeElapsed/1000)+"\t s"+"\t parallel = \t "+process_series_in_parallel+"\t nProcessors = \t"+nThreads);
    }

}
