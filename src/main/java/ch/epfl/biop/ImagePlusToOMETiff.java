package ch.epfl.biop;

import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.kheops.KheopsHelper;
import ch.epfl.biop.kheops.ometiff.OMETiffExporter;
import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import loci.common.DebugTools;
import ome.units.quantity.Length;
import ome.units.unit.Unit;
import org.scijava.task.TaskService;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

public class ImagePlusToOMETiff {

    public static Consumer<String> logger = IJ::log;

    // Builder class
    public static class Builder {
        // Required parameters
        private final ImagePlus image;
        private final File file;

        // Optional parameters with default values
        private String compression = "LZW";
        private boolean createMultipleResolutions = true;
        private TaskService taskService = null;
        private int tileSize = 512;
        private int nThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        private int numberOfBlocksComputedInAdvance = 64;
        private boolean compressTemporaryFiles = false;
        private int downsampleFactor = 2;
        private Consumer<String> customLogger = logger;

        public Builder(ImagePlus image, File file) {
            this.image = image;
            this.file = file;
        }

        public Builder compression(String compression) {
            this.compression = compression;
            return this;
        }

        public Builder createMultipleResolutions(boolean createMultipleResolutions) {
            this.createMultipleResolutions = createMultipleResolutions;
            return this;
        }

        public Builder taskService(TaskService taskService) {
            this.taskService = taskService;
            return this;
        }

        public Builder tileSize(int tileSize) {
            this.tileSize = tileSize;
            return this;
        }

        public Builder nThreads(int nThreads) {
            this.nThreads = nThreads;
            return this;
        }

        public Builder numberOfBlocksComputedInAdvance(int numberOfBlocksComputedInAdvance) {
            this.numberOfBlocksComputedInAdvance = numberOfBlocksComputedInAdvance;
            return this;
        }

        public Builder compressTemporaryFiles(boolean compressTemporaryFiles) {
            this.compressTemporaryFiles = compressTemporaryFiles;
            return this;
        }

        public Builder downsampleFactor(int downsampleFactor) {
            this.downsampleFactor = downsampleFactor;
            return this;
        }

        public Builder logger(Consumer<String> logger) {
            this.customLogger = logger;
            return this;
        }

        public OMETiffExportJob build() {
            return new OMETiffExportJob(this);
        }
    }

    // Immutable export job class
    public static class OMETiffExportJob {
        private final ImagePlus image;
        private final File file;
        private final String compression;
        private final boolean createMultipleResolutions;
        private final TaskService taskService;
        private final int tileSize;
        private final int nThreads;
        private final int numberOfBlocksComputedInAdvance;
        private final boolean compressTemporaryFiles;
        private final int downsampleFactor;
        private final Consumer<String> customLogger;

        private OMETiffExportJob(Builder builder) {
            this.image = builder.image;
            this.file = builder.file;
            this.compression = builder.compression;
            this.createMultipleResolutions = builder.createMultipleResolutions;
            this.taskService = builder.taskService;
            this.tileSize = builder.tileSize;
            this.nThreads = builder.nThreads;
            this.numberOfBlocksComputedInAdvance = builder.numberOfBlocksComputedInAdvance;
            this.compressTemporaryFiles = builder.compressTemporaryFiles;
            this.downsampleFactor = builder.downsampleFactor;
            this.customLogger = builder.customLogger;
        }

        public void execute() {
            writeToOMETiff();
        }

        private void writeToOMETiff() {
            Instant start = Instant.now();
            String imageTitle = image.getTitle();

            File output_dir = new File(file.getParent());

            if (!output_dir.exists()) output_dir.mkdirs();

            DebugTools.enableLogging("WARN");

            KheopsHelper.SourcesInfo sourcesInfo =
                    KheopsHelper.getSourcesFromImage(image, numberOfBlocksComputedInAdvance, nThreads);

            String fileNameWithOutExt = imageTitle;

            SourceAndConverter[] sources = sourcesInfo.idToSources.get(0).toArray(new SourceAndConverter[0]);

            File output_path = new File(output_dir, fileNameWithOutExt + ".ome.tiff");

            if (output_path.exists()) {
                IJ.log("Error: file " + output_path.getAbsolutePath() + " already exists. Skipped!");
            } else {

                int sizeFullResolution = (int) Math.min(
                        sources[0].getSpimSource().getSource(0, 0).max(0),
                        sources[0].getSpimSource().getSource(0, 0).max(1)
                );

                int nResolutions = 1;

                if (createMultipleResolutions) {
                    while (sizeFullResolution > tileSize) {
                        sizeFullResolution /= downsampleFactor;
                        nResolutions++;
                    }
                }

                try {
                    if (image.getCalibration() == null) image.setCalibration(new Calibration());

                    Unit<Length> u = KheopsHelper.getUnitFromCalibration(image.getCalibration());

                    OMETiffExporter.OMETiffExporterBuilder.MetaData.MetaDataBuilder builder =
                            OMETiffExporter.builder()
                                    .put(sources)
                                    .defineMetaData(image.getTitle())
                                    .putMetadataFromSources(sources, u)
                                    .pixelsTimeIncrementInS(image.getCalibration().frameInterval);

                    OMETiffExporter exporter = builder.defineWriteOptions()
                            .maxTilesInQueue(numberOfBlocksComputedInAdvance)
                            .compression(compression)
                            .compressTemporaryFiles(compressTemporaryFiles)
                            .nThreads(nThreads)
                            .downsample(downsampleFactor)
                            .nResolutionLevels(nResolutions)
                            .monitor(taskService)
                            .savePath(output_path.getAbsolutePath())
                            .tileSize(tileSize, tileSize)
                            .create();

                    exporter.export();

                } catch (Exception e) {
                    IJ.log("Error with " + output_path + " export: " + e.getMessage());
                }
            }

            Instant finish = Instant.now();
            long timeElapsed = Duration.between(start, finish).toMillis();
            customLogger.accept(imageTitle + "\t OME TIFF conversion (Kheops) \t Run time=\t" +
                    (timeElapsed / 1000) + "\t s" + "\t parallel = \t " + false +
                    "\t nProcessors = \t" + nThreads);
        }
    }

    // Convenience method that maintains backward compatibility
    @Deprecated
    public static void writeToOMETiff(ImagePlus image, File file, String compression,
                                      boolean createMultipleResolutions, TaskService taskService) {
        new Builder(image, file)
                .compression(compression)
                .createMultipleResolutions(createMultipleResolutions)
                .taskService(taskService)
                .build()
                .execute();
    }

    // Factory method for creating a builder
    public static Builder builder(ImagePlus image, File file) {
        return new Builder(image, file);
    }
}

