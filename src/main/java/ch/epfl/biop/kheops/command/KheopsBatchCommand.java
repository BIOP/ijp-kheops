/*-
 * #%L
 * IJ2 commands that use bio-formats to create pyramidal ome.tiff
 * %%
 * Copyright (C) 2018 - 2026 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP)
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
import loci.formats.FormatException;
import loci.formats.IFormatReader;
import loci.formats.in.OIRReader;
import loci.formats.meta.IMetadata;
import org.apache.commons.io.FilenameUtils;
import org.scijava.Context;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.task.Task;
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

import static ch.epfl.biop.kheops.KheopsHelper.transferSeriesMeta;

@SuppressWarnings("CanBeFinal")
@Plugin(type = Command.class,
        menuPath = "Plugins>BIOP>Kheops>Kheops - Batch Convert Files to Pyramidal OME TIFF",
        description = "Converts many Bio-Formats readable files to pyramidal OME-TIFF files, one file per CPU core.")
public class KheopsBatchCommand implements Command {

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false, required = false)
    String documentation = "<html><h3>Batch convert files to pyramidal OME-TIFF</h3>" +
            "Same conversion as <b>Kheops - Convert File to Pyramidal OME TIFF</b>, but several files are " +
            "processed <b>at the same time</b>, one per CPU core. Reading and writing then overlap, which is " +
            "the fastest option when you have many files.<br>" +
            "It pays off only if you have <b>more files than cores</b>; for a handful of large files, the single " +
            "file command (which parallelizes inside one file) is faster.<br>" +
            "For maximum speed, read from one drive and write to another one.<br>" +
            "<i>An output file that already exists is never overwritten: it is skipped, and a message is " +
            "printed in the Log window.</i></html>";

    @Parameter(label = "Input files (required)", style = "open",
            description = "The files to convert. Any format readable by Bio-Formats works, and the files do not need to be of the same format.")
    File[] input_paths;

    @Parameter(label = "Output folder (optional)", style = "directory", required = false,
            description = "Where the .ome.tiff files are written. Leave empty to write them next to the first input file. The folder is created if it does not exist.")
    File output_dir;

    @Parameter(label = "Compression type", choices = {"LZW", "Uncompressed", "JPEG-2000", "JPEG-2000 Lossy", "JPEG"},
            description = "LZW: lossless, the safe default. Uncompressed: fastest to write, biggest files. " +
                    "JPEG-2000: lossless but slow. JPEG-2000 Lossy and JPEG: smallest files, but pixel values are modified (JPEG only handles 8-bit and RGB images).")
    String compression = "LZW";

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false, required = false)
    String message = "<html><b>Select a subset of series, channels, slices and timepoints. Leave a field blank to export all of them.</b><br>" +
            "The same subset is applied to <b>every</b> selected file. Indices start at <b>0</b>, and <b>-1</b> is " +
            "the last one. Use a comma to list indices and a colon to write a range (bounds included):<br>" +
            "<code>0,3,5</code> &#8594; indices 0, 3 and 5 &nbsp;&nbsp; <code>2:6</code> &#8594; 2, 3, 4, 5, 6 " +
            "&nbsp;&nbsp; <code>0:2:10</code> &#8594; 0, 2, 4, 6, 8, 10 (step 2) &nbsp;&nbsp; <code>0:-1</code> &#8594; everything.</html>";

    @Parameter( label = "Series subset", required = false,
            description = "Indices of the series to convert in each file, e.g. '0' for the first series only. Blank = all series." )
    String subset_series = "";

    @Parameter( label = "Channels subset", required = false,
            description = "Indices of the channels to keep in each converted file. Blank = all channels." )
    String subset_channels = "";

    @Parameter( label = "Slices subset", required = false,
            description = "Indices of the Z slices to keep in each converted file. Blank = all slices." )
    String subset_slices = "";

    @Parameter( label = "Timepoints subset", required = false,
            description = "Indices of the timepoints to keep in each converted file. Blank = all timepoints." )
    String subset_frames = "";

    @Parameter(label = "Override voxel sizes",
            description = "Check this if the input files are not calibrated, or if their calibration is wrong. The two values below are then used for all converted files.")
    boolean override_voxel_size;

    @Parameter(label = "XY voxel size (micrometer)", style = "format:0.000",
            description = "Physical size of a pixel along X and Y, in micrometer. Used only if 'Override voxel sizes' is checked.")
    double vox_size_xy;

    @Parameter(label = "Z voxel size (micrometer)", style = "format:0.000",
            description = "Distance between two Z slices, in micrometer. Used only if 'Override voxel sizes' is checked.")
    double vox_size_z;

    final Set<String> paths = new HashSet<>();

    @Parameter
    TaskService taskService;

    @Parameter
    LogService logService;

    @Parameter
    Context context;

    final Object cancelConcatenatorLock = new Object();

    @Override
    public void run() {

        //--------------------
        int tileSize = 512;
        int nThreads = Math.max(1,Runtime.getRuntime().availableProcessors()-1);
        // Files are always exported in parallel here, and each export gets a single worker
        // thread: a smaller queue keeps the memory footprint close to the one of a serial export
        int numberOfBlocksComputedInAdvance = 4;

        Instant start = Instant.now();

        DebugTools.enableLogging("OFF");

        if (input_paths.length<(Runtime.getRuntime().availableProcessors()+1)/2) {
            IJ.log("You selected a few files only, the batch kheops command may be slower than the normal kheops with the batch button");
        }

        if ((output_dir == null) || (output_dir.toString().isEmpty())) {
            output_dir = new File(input_paths[0].getParent());
        } else {
            output_dir.mkdirs();
        }

        Task iniBatchTask = null;
        int nFiles = input_paths.length;
        if (nFiles>1) {
            iniBatchTask = taskService.createTask("OME-Tiff export of " + nFiles + " files.");
            iniBatchTask.setProgressMaximum(nFiles);
            iniBatchTask.start();
        }
        Task batchTask = iniBatchTask;
        try {
            ForkJoinPool customThreadPool = new ForkJoinPool(nThreads);
            try {
                customThreadPool.submit(() -> {
                    Arrays.asList(input_paths).parallelStream().forEach(input_path -> {
                        if ((batchTask==null)||(!batchTask.isCanceled())) {
                            IJ.log("Processing " + input_path);
                            String fileName = input_path.getName();

                            final KheopsHelper.SourcesInfo sourcesInfo =
                                        KheopsHelper
                                                .getSourcesFromFile(input_path.getAbsolutePath(), tileSize, tileSize, 1,
                                                        1, false, "CORNER", context);

                            int nSeriesOriginal = sourcesInfo.idToSources.keySet().size();

                            List<Integer> series;
                            try {
                                series = new IntRangeParser(subset_series).get(nSeriesOriginal);
                            } catch (Exception e) {
                                e.printStackTrace();
                                throw new RuntimeException(e);
                            }

                            series.forEach(iSeries -> {
                                if ((batchTask==null)||(!batchTask.isCanceled())) {
                                    String fileNameWithOutExt = FilenameUtils.removeExtension(fileName);
                                    SourceAndConverter[] sources = sourcesInfo.idToSources.get(iSeries).toArray(new SourceAndConverter[0]);

                                    if (series.size() > 1) {
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
                                            fileNameWithOutExt += "_s" + iSeries;
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
                                    }

                                    fileNameWithOutExt += ".ome.tiff";

                                    File output_path = new File(output_dir, fileNameWithOutExt);

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
                                            OMETiffExporter.OMETiffExporterBuilder.MetaData.MetaDataBuilder builder = OMETiffExporter.builder()
                                                    .put(sources)
                                                    //.setReaderPool(sourcesInfo.readerPool, iSeries)
                                                    .defineMetaData("Image")
                                                    .applyOnMeta(meta -> {
                                                        IFormatReader reader = null;
                                                        try {
                                                            try {
                                                                reader = sourcesInfo.readerPool.acquire();
                                                                IMetadata medataSrc = (IMetadata) reader.getMetadataStore();
                                                                transferSeriesMeta(medataSrc, iSeries, meta, 0);
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
                                            OMETiffExporter exporter = builder.defineWriteOptions()
                                                    .maxTilesInQueue(numberOfBlocksComputedInAdvance)
                                                    .compression(compression)
                                                    .nThreads(1)
                                                    .downsample(2)
                                                    .nResolutionLevels(nResolutions)
                                                    .rangeT(subset_frames)
                                                    .rangeC(subset_channels)
                                                    .rangeZ(subset_slices)
                                                    .monitor(taskService)
                                                    .savePath(output_path.getAbsolutePath())
                                                    .tileSize(tileSize, tileSize).create();

                                            if (batchTask!=null) {
                                                synchronized (cancelConcatenatorLock) {
                                                    Runnable callback = batchTask.getCancelCallBack();
                                                    batchTask.setCancelCallBack(() -> {
                                                        callback.run();
                                                        exporter.cancelExport();
                                                    });
                                                }
                                            }

                                            exporter.export();

                                        } catch (Exception e) {
                                            IJ.log("Error with " + fileNameWithOutExt + " export.");
                                            e.printStackTrace();
                                        }
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

                            if (batchTask!=null) {
                                synchronized (cancelConcatenatorLock) {
                                    batchTask.setProgressValue(batchTask.getProgressValue() + 1);
                                }
                            }
                        }
                    });

                    return 0;
                }).get();
            } catch (Exception e) {
                e.printStackTrace();
            }

            customThreadPool.shutdown();

            // CODE HERE
            Instant finish = Instant.now();
            long timeElapsed = Duration.between(start, finish).toMillis();
            IJ.log("\t Batch OME TIFF conversion (Kheops) \t Run time=\t" + (timeElapsed / 1000) + "\t s ");
            KheopsHelper.writeElapsedTime(start,
                    (str) -> logService.subLogger(this.getClass().getSimpleName()).info(str),
                    "Batch export time:");
        } finally {
            if (batchTask!=null) batchTask.finish();
        }
    }

}
