package ch.epfl.biop.kheops.command;

import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.kheops.KheopsHelper;
import ch.epfl.biop.kheops.OMETiffPyramidizerExporter;
import ij.IJ;
import loci.common.DebugTools;
import net.imagej.ImageJ;
import org.apache.commons.io.FilenameUtils;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Creates a pyramidal OME TIFF, with some optimisations
 *
 * TODO documentation or link to documentation
 * </p>
 */

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Kheops>Kheops - Batch Convert File to Pyramidal OME")
public class KheopsBatchCommand implements Command {

    @Parameter(label = "Select input files (required)", style="open")
    File[] input_paths;

    @Parameter(label= "Specify an output folder (optional)", style = "directory", required=false, persist=false)
    File output_dir;

    @Parameter(label="Override voxel sizes")
    boolean override_voxel_size;

    @Parameter(label="Voxel size in micrometer (XY)", style="format:#.000")
    double vox_size_xy;

    @Parameter(label="Voxel Z size in micrometer (Z)", style="format:#.000")
    double vox_size_z;

    Set<String> paths = new HashSet<>();
    public static Consumer<String> logger = (str) -> IJ.log(str);

    @Override
    public void run() {

        //--------------------
        int tileSize = 512;

        Instant start = Instant.now();

        DebugTools.enableLogging("OFF");

        if (input_paths.length<(Runtime.getRuntime().availableProcessors()+1)/2) {
            IJ.log("You selected a few files only, the batch mode may be slower than the normal mode with the batch button");
        }

        if ((output_dir == null) || (output_dir.toString().equals(""))) {
            output_dir = new File(input_paths[0].getParent());
        } else {
            output_dir.mkdirs();
        }

        Arrays.asList(input_paths).parallelStream().forEach(input_path -> {

            String fileName = input_path.getName();

            KheopsHelper.SourcesInfo sourcesInfo =
                    KheopsHelper.getSourcesFromFile(input_path.getAbsolutePath(), tileSize, tileSize, 10,1);

            int nSeries = sourcesInfo.idToSources.keySet().size();

            IntStream idStream =  IntStream.range(0,nSeries);

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
                            .lzw()
                            .nThreads(0)
                            .downsample(2)
                            .nResolutionLevels(nResolutions)
                            .micrometer()
                            .savePath(output_path.getAbsolutePath())
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
        });


        // CODE HERE
        Instant finish = Instant.now();
        long timeElapsed = Duration.between(start, finish).toMillis();
        logger.accept("\t Batch OME TIFF conversion (Kheops) \t Run time=\t"+(timeElapsed/1000)+"\t s ");
    }

}