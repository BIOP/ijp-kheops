/*-
 * #%L
 * IJ2 commands that use bio-formats to create pyramidal ome.tiff
 * %%
 * Copyright (C) 2018 - 2025 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP)
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
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.task.Task;
import org.scijava.task.TaskService;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static ch.epfl.biop.kheops.KheopsHelper.transferSeriesMeta;


@SuppressWarnings("CanBeFinal")
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Kheops>Kheops - Convert File to Pyramidal OME TIFF",
        description = "Converts a Bio-Formats readable file to pyramidal OME TIFFs files (one file per series).")
public class KheopsCommand implements Command {
    @Parameter(label = "Select an input file (required)", style="open")
    File input_path;

    @Parameter(label= "Output folder (optional)", style = "directory", required=false)
    File output_dir;

    @Parameter(label="Compression type", choices = {"LZW", "Uncompressed", "JPEG-2000", "JPEG-2000 Lossy", "JPEG"})
    String compression = "LZW";

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false, required = false)
    String message = "<html><b>Subset in CTZ and series. Leave fields blank to export all.<br></b>" +
            "You can use commas or colons to separate ranges. eg. '1:2:10' or '1,3,5,8'. '-1' is the last index.</html>";

    @Parameter( label = "Series subset:", required = false )
    String subset_series = "";
    @Parameter( label = "Channels subset:", required = false )
    String subset_channels = "";

    @Parameter( label = "Slices subset:", required = false )
    String subset_slices = "";

    @Parameter( label = "Timepoints subset:", required = false )
    String subset_frames = "";

    @Parameter( label = "Split Channels:", required = false )
    boolean split_channels = false;

    @Parameter( label = "Split Slices:", required = false )
    boolean split_slices = false;

    @Parameter( label = "Split Timepoints:", required = false )
    boolean split_frames = false;

    //@Parameter( label = "Parallel Export When Split:", required = false )
    boolean parallel_split_export = false; // Couldn't observe any performance gain with true

    @Parameter(label="Compress temporary files (LZW)")
    boolean compress_temp_files = false;

    @Parameter(label="Override voxel sizes")
    boolean override_voxel_size;

    @Parameter(label="XY Voxel size in micrometer", style="format:0.000")
    double vox_size_xy;

    @Parameter(label="Z Voxel size in micrometer", style="format:0.000")
    double vox_size_z;

    public static Consumer<String> logger = IJ::log;

    @Parameter
    TaskService taskService;

    @Parameter
    Context context;

    final Object cancelConcatenatorLock = new Object();

    @Override
    public void run() {

        Instant start = Instant.now();
        String fileName = input_path.getName();

        //--------------------
        int tileSize = 1024;
        int nThreads = Math.max(1,Runtime.getRuntime().availableProcessors()-1);

        File parent_dir = new File(input_path.getParent());

        if ((output_dir == null) || (output_dir.toString().isEmpty())) {
            output_dir = parent_dir;
        } else {
            output_dir.mkdirs();
        }

        DebugTools.enableLogging("OFF");

        int numberOfBlocksComputedInAdvance = 64;

        final KheopsHelper.SourcesInfo sourcesInfo =
                    KheopsHelper
                            .getSourcesFromFile(input_path.getAbsolutePath(), tileSize, tileSize, numberOfBlocksComputedInAdvance,
                                    1, false, "CORNER", context);

        int nSeriesOriginal = sourcesInfo.idToSources.keySet().size();

        List<Integer> series;
        try {
            series = new IntRangeParser(subset_series).get(nSeriesOriginal);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        boolean process_series_in_parallel = (series.size() != 1);

        Map<Integer, String> indexToFilePath = new HashMap<>();
        Set<String> paths = new HashSet<>();

        Task iniBatchTask = null;
        int sSeries = series.size();
        if (sSeries>1) {
            iniBatchTask = taskService.createTask("OME-Tiff export of " + input_path.getName());
            iniBatchTask.setProgressMaximum(series.size());
            iniBatchTask.start();
        }
        Task batchTask = iniBatchTask;

        try {
            series.forEach(iSeries -> {
                String fileNameWithOutExt = FilenameUtils.removeExtension(fileName);
                if (series.size() > 1) {
                    if (sourcesInfo.idToSeriesIndex.containsKey(iSeries)) {
                        fileNameWithOutExt += "_" + sourcesInfo.idToImageName.get(sourcesInfo.seriesToId.get(iSeries)).getName();
                    } else {
                        fileNameWithOutExt += "_" + iSeries;
                    }
                }
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
                indexToFilePath.put(iSeries, fileNameWithOutExt );
            });

            Stream<Integer> idStream = series.stream();

            if (process_series_in_parallel) idStream = idStream.parallel();

            final boolean parallelProcess = process_series_in_parallel;

            idStream.forEach(iSeries -> {
                if ((batchTask==null)||(!batchTask.isCanceled())) {
                    SourceAndConverter[] sources = sourcesInfo.idToSources.get(iSeries).toArray(new SourceAndConverter[0]);

                    File output_path = new File(output_dir, indexToFilePath.get(iSeries));

                    if (output_path.exists()) {
                        IJ.log("Error: file " + output_path.getAbsolutePath() + " already exists. Skipped!");
                    } else {
                        Task export = taskService.createTask("Export " + fileName);
                        try {


                            int nTimepoints = 0;
                            while (sources[0].getSpimSource().isPresent(nTimepoints)) nTimepoints++;

                            CZTSetIterator iterator = new CZTSetIterator(subset_channels, subset_slices, subset_frames, split_channels, split_slices, split_frames, sources.length, (int) sources[0].getSpimSource().getSource(0,0).max(2), nTimepoints);

                            List<CZTSet> sets = new ArrayList<>();
                            for (CZTSetIterator it = iterator; it.hasNext(); ) {
                                CZTSet set = it.next();
                                sets.add(set);
                            }

                            AtomicInteger counter = new AtomicInteger();
                            int nExportedFiles = sets.size();

                            export.setProgressMaximum(nExportedFiles);

                            Stream<CZTSet> stream;

                            final boolean finalParallelProcess;

                            if (parallel_split_export) {
                                stream = sets.stream().parallel();
                                finalParallelProcess = false;
                            } else {
                                stream = sets.stream();
                                finalParallelProcess = parallelProcess;
                            }

                            stream.forEach(set -> {
                                if (export.isCanceled()) return;
                                try {
                                    try {

                                        int sizeFullResolution = (int) Math.min(sources[0].getSpimSource().getSource(0, 0).max(0), sources[0].getSpimSource().getSource(0, 0).max(1));

                                        int nResolutions = 1;

                                        while (sizeFullResolution > tileSize) {
                                            sizeFullResolution /= 2;
                                            nResolutions++;
                                        }

                                        OMETiffExporter.OMETiffExporterBuilder.MetaData.MetaDataBuilder builder = OMETiffExporter.builder()
                                                .put(sources)
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
                                                .compressTemporaryFiles(compress_temp_files)
                                                .nThreads(finalParallelProcess ? 0 : nThreads)
                                                .downsample(2)
                                                .nResolutionLevels(nResolutions)
                                                .rangeT(set.frames_set)
                                                .rangeC(set.channels_set)
                                                .rangeZ(set.slices_set)
                                                .monitor(taskService)
                                                .savePath(output_path.getAbsolutePath()+optionalSubSetPattern(set)+".ome.tiff")
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
                                        if (batchTask!=null) {
                                            synchronized (cancelConcatenatorLock) {
                                                batchTask.setProgressValue(batchTask.getProgressValue() + 1);
                                            }
                                        }

                                    } catch (Exception e) {
                                        IJ.log("Error with " + output_path + " export: "+e.getMessage());
                                        if (batchTask!=null) {
                                            synchronized (cancelConcatenatorLock) {
                                                batchTask.setProgressValue(batchTask.getProgressValue() + 1);
                                            }
                                        }
                                        e.printStackTrace();
                                    }
                                    export.setProgressValue(counter.incrementAndGet());

                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            });

                        }  catch(Exception e) {
                            e.printStackTrace();
                        } finally {
                            export.finish();
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
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (batchTask!=null) batchTask.finish();
        }
        // CODE HERE
        Instant finish = Instant.now();
        long timeElapsed = Duration.between(start, finish).toMillis();
        logger.accept(input_path.getName()+"\t OME TIFF conversion (Kheops) \t Run time=\t"+(timeElapsed/1000)+"\t s"+"\t parallel = \t "+process_series_in_parallel+"\t nProcessors = \t"+nThreads);
    }

    private String optionalSubSetPattern(CZTSet set) {
        String output = "";
        if (!set.channels_set.isEmpty()) {
            output+="_C"+set.channels_set;
        }
        if (!set.slices_set.isEmpty()) {
            output+="_Z"+set.slices_set;
        }
        if (!set.frames_set.isEmpty()) {
            output+="_T"+set.frames_set;
        }
        return output;
    }

    static class CZTSetIterator implements Iterator<CZTSet> {

        CZTSet iniSet;

        List<Integer> lC = new ArrayList<>();
        List<Integer> lZ = new ArrayList<>();
        List<Integer> lT = new ArrayList<>();

        int iC = 0, iZ = -1, iT = 0, nC, nZ, nT;

        final boolean splitC,splitZ,splitT;

        CZTSetIterator(String range_channels, String range_slices, String range_frames,
                       boolean splitC, boolean splitZ, boolean splitT,
                       int nC, int nZ, int nT) throws Exception{
            iniSet = new CZTSet();

            this.splitC = splitC;
            this.splitZ = splitZ;
            this.splitT = splitT;

            iniSet.channels_set = range_channels;
            iniSet.slices_set = range_slices;
            iniSet.frames_set = range_frames;

            if (splitC) {
                lC = new IntRangeParser(range_channels).get(nC);
                this.nC = lC.size();
            } else this.nC = 1;
            if (splitZ) {
                lZ = new IntRangeParser(range_slices).get(nZ);
                this.nZ = lZ.size();
            } else this.nZ = 1;
            if (splitT) {
                lT = new IntRangeParser(range_frames).get(nT);
                this.nT = lT.size();
            } else this.nT = 1;
        }


        @Override
        public boolean hasNext() {
            return !((iC == nC-1)&&(iZ == nZ-1)&&(iT==nT-1));
        }

        @Override
        public CZTSet next() {
            CZTSet next = new CZTSet(iniSet);
            // T Z C
            iZ++;
            if (iZ==nZ) {
                iZ=0;
                iC++;
                if (iC==nC) {
                    iC=0;
                    iT++;
                    if (iT==nT) {
                        return null; // Done!
                    }
                }
            }
            if (splitC) next.channels_set = lC.get(iC).toString();
            if (splitZ) next.slices_set = lZ.get(iZ).toString();
            if (splitT) next.frames_set = lT.get(iT).toString();
            return next;
        }
    }

    static class CZTSet {
        String channels_set = "";
        String slices_set = "";
        String frames_set = "";

        public CZTSet() {

        }

        public CZTSet(CZTSet ini) {
            this.channels_set = ini.channels_set;
            this.slices_set = ini.slices_set;
            this.frames_set = ini.frames_set;
        }

        @Override
        public String toString() {
            return "C:\t"+channels_set+"\tZ:\t"+slices_set+"\tT\t:"+frames_set;
        }

    }

}
