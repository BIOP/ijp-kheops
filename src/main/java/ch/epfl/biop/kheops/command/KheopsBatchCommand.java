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
package ch.epfl.biop.kheops.command;

import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.kheops.IntRangeParser;
import ch.epfl.biop.kheops.KheopsHelper;
import ch.epfl.biop.kheops.ometiff.OMETiffExporter;
import ij.IJ;
import loci.common.DebugTools;
import loci.formats.IFormatReader;
import loci.formats.meta.IMetadata;
import org.apache.commons.io.FilenameUtils;
import org.scijava.Context;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.task.TaskService;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

import static ch.epfl.biop.kheops.KheopsHelper.copyFromMetaSeries;

@Plugin(type = Command.class,
        menuPath = "Plugins>BIOP>Kheops>Kheops - Batch Convert Files to Pyramidal OME TIFF",
        description = "Converts in parallel Bio-Formats readable files to pyramidal OME TIFFs files.")
public class KheopsBatchCommand implements Command {

    @Parameter(label = "Select input files (required)", style="open")
    File[] input_paths;

    @Parameter( label = "Selected Series. Leave blank for all", required = false )
    String range_series = "";

    @Parameter( label = "Selected Channels. Leave blank for all", required = false )
    String range_channels = "";

    @Parameter( label = "Selected Slices. Leave blank for all", required = false )
    String range_slices = "";

    @Parameter( label = "Selected Timepoints. Leave blank for all", required = false )
    String range_frames = "";

    @Parameter(label= "Specify an output folder (optional)", style = "directory", required=false)
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

    @Parameter
    LogService logService;

    @Parameter
    Context context;

    @Override
    public void run() {

        //--------------------
        int tileSize = 512;
        int nThreads = Math.max(1,Runtime.getRuntime().availableProcessors()-1);

        Instant start = Instant.now();

        DebugTools.enableLogging("OFF");

        if (input_paths.length<(Runtime.getRuntime().availableProcessors()+1)/2) {
            IJ.log("You selected a few files only, the batch kheops command may be slower than the normal kheops with the batch button");
        }

        if ((output_dir == null) || (output_dir.toString().equals(""))) {
            output_dir = new File(input_paths[0].getParent());
        } else {
            output_dir.mkdirs();
        }

        ForkJoinPool customThreadPool = new ForkJoinPool(nThreads);
        try {
            customThreadPool.submit(() -> {

                Arrays.asList(input_paths).parallelStream().forEach(input_path -> {

                    IJ.log("Processing "+input_path);
                    String fileName = input_path.getName();

                    KheopsHelper.SourcesInfo sourcesInfo =
                            KheopsHelper
                                    .getSourcesFromFile(input_path.getAbsolutePath(), tileSize, tileSize, 1,
                                            1,false, "CORNER", context);

                    int nSeriesOriginal = sourcesInfo.idToSources.keySet().size();

                    List<Integer> series;
                    try {
                        series = new IntRangeParser(range_series).get(nSeriesOriginal);
                    } catch (Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }

                    series.forEach(iSeries -> {

                        String fileNameWithOutExt = FilenameUtils.removeExtension(fileName);
                        SourceAndConverter[] sources = sourcesInfo.idToSources.get(iSeries).toArray(new SourceAndConverter[0]);

                        if (series.size()>1) {
                            if (sourcesInfo.idToSeriesIndex.containsKey(iSeries)) {
                                fileNameWithOutExt += "_" + sourcesInfo.idToImageName.get(sourcesInfo.seriesToId.get(iSeries)).getName();
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

                        if (output_path.exists()) {
                            IJ.log("Error: file "+output_path.getAbsolutePath()+" already exists. Skipped!");
                        } else {
                            int sizeFullResolution = (int) Math.min(sources[0].getSpimSource().getSource(0,0).max(0),sources[0].getSpimSource().getSource(0,0).max(1));

                            int nResolutions = 1;

                            while (sizeFullResolution>tileSize) {
                                sizeFullResolution/=2;
                                nResolutions++;
                            }

                            try {
                                OMETiffExporter.OMETiffExporterBuilder.MetaData.MetaDataBuilder builder = OMETiffExporter.builder().defineData()
                                        .put(sources)
                                        .setReaderPool(sourcesInfo.readerPool, iSeries)
                                        .defineMetaData("Image")
                                        .applyOnMeta(meta -> {
                                            IFormatReader reader = null;
                                            try {
                                                try {
                                                    reader = sourcesInfo.readerPool.acquire();
                                                    IMetadata medataSrc = (IMetadata) reader.getMetadataStore();
                                                    copyFromMetaSeries(medataSrc, iSeries, meta, 0);
                                                } finally {
                                                    sourcesInfo.readerPool.recycle(reader);
                                                }
                                            } catch (Exception e) {
                                                e.printStackTrace();
                                            }
                                            return meta;
                                        });
                                if (override_voxel_size) {
                                    builder.voxelPhysicalSizeMicrometer(this.vox_size_xy, this.vox_size_xy, this.vox_size_z);
                                }
                                builder.defineWriteOptions()
                                        .compression(compression)
                                        .compressTemporaryFiles(compress_temp_files)
                                        .nThreads(0)
                                        .downsample(2)
                                        .nResolutionLevels(nResolutions)
                                        .rangeT(range_frames)
                                        .rangeC(range_channels)
                                        .rangeZ(range_slices)
                                        .monitor(taskService)
                                        .savePath(output_path.getAbsolutePath())
                                        .tileSize(tileSize, tileSize).create().export();
                            } catch (Exception e) {
                                IJ.log("Error with "+fileNameWithOutExt+" export.");
                                e.printStackTrace();
                            }
                        }

                    });
                    sourcesInfo.readerPool.shutDown(reader -> {
                        try {
                            reader.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
                });

            return 0;}).get();
        } catch (Exception e) {
            e.printStackTrace();
        }

        customThreadPool.shutdown();

        // CODE HERE
        Instant finish = Instant.now();
        long timeElapsed = Duration.between(start, finish).toMillis();
        logger.accept("\t Batch OME TIFF conversion (Kheops) \t Run time=\t"+(timeElapsed/1000)+"\t s ");
        KheopsHelper.writeElapsedTime(start,
                logService.subLogger(this.getClass().getSimpleName()),
                "Batch export time:");
    }

}
