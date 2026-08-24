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
import ch.epfl.biop.kheops.KheopsHelper;
import ch.epfl.biop.kheops.ometiff.OMETiffExporter;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.units.unit.Unit;
import org.apache.commons.io.FilenameUtils;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.task.TaskService;

import java.io.File;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@SuppressWarnings({ "Unused", "CanBeFinal" })
@Plugin(type = Command.class,
        menuPath = "Plugins>BigDataViewer-Playground>Export>Source - Export To OME-TIFF",
        description = "Saves BDV sources as a single multi-channel pyramidal OME-TIFF file, with the resolution " +
                "levels recomputed from the highest resolution level.")
public class KheopsExportSourcesCommand implements Command {

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false, required = false)
    String documentation = "<html><body style='width: 620px'><h3>Export BDV sources to a pyramidal OME-TIFF</h3>" +
            "Writes the selected <b>BigDataViewer sources</b> into a single tiled, pyramidal <b>.ome.tiff</b>, " +
            "each source becoming one <b>channel</b> of the output file.<br>" +
            "All sources must share the same dimensions and the same pixel type; the resolution levels of the " +
            "output are recomputed by averaging from the highest resolution level of the sources, so any " +
            "transformation applied to a source (registration, warping, ...) is baked into the exported pixels.<br>" +
            "<i>An output file that already exists is never overwritten: it is skipped, and a message is " +
            "printed in the Log window.</i></body></html>";

    @Parameter(label = "Sources to export",
            description = "The BDV sources to write. Each source becomes one channel of the output file.")
    public SourceAndConverter[] sources;

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false, required = false)
    String message = "<html><body style='width: 620px'><b>Select a subset of channels, slices and timepoints. Leave a field blank to export all of them.</b><br>" +
            "Indices start at <b>0</b>, and <b>-1</b> is the last one. Use a comma to list indices and a colon to " +
            "write a range (bounds included):<br>" +
            "<code>0,3,5</code> &#8594; indices 0, 3 and 5 &nbsp;&nbsp; <code>2:6</code> &#8594; 2, 3, 4, 5, 6 " +
            "&nbsp;&nbsp; <code>0:2:10</code> &#8594; 0, 2, 4, 6, 8, 10 (step 2) &nbsp;&nbsp; <code>0:-1</code> &#8594; everything.</body></html>";

    @Parameter(label = "Channels subset", required = false,
            description = "Indices of the sources to export, in the order they were selected. Blank = all of them.")
    String range_channels = "";

    @Parameter(label = "Slices subset", required = false,
            description = "Indices of the Z slices to export. Blank = all slices.")
    String range_slices = "";

    @Parameter(label = "Timepoints subset", required = false,
            description = "Indices of the timepoints to export. Blank = all timepoints.")
    String range_frames = "";

    @Parameter(label = "Output file", style = "save",
            description = "Path of the file to write. The '.ome.tiff' extension is expected; the file name (without extension) is used as the OME image name.")
    public File file;

    @Parameter(label = "Physical unit", choices = { "MILLIMETER", "MICROMETER" },
            description = "Unit written in the OME metadata for the voxel size read from the sources.")
    String unit;

    @Parameter(label = "Override voxel sizes",
            description = "Check this to ignore the voxel size of the sources and use the two values below instead.")
    boolean override_voxel_size;

    @Parameter(label = "XY voxel size (micrometer)", style = "format:#.000",
            description = "Physical size of a pixel along X and Y, in micrometer. Used only if 'Override voxel sizes' is checked.")
    double vox_size_xy_um;

    @Parameter(label = "Z voxel size (micrometer)", style = "format:#.000",
            description = "Distance between two Z slices, in micrometer. Used only if 'Override voxel sizes' is checked.")
    double vox_size_z_um;

    @Parameter(label = "Number of resolution levels",
            description = "Number of levels in the pyramid, the full resolution level included. Use 1 to write a single resolution file.")
    int n_resolution_levels = 4;

    @Parameter(label = "Scaling factor between resolution levels",
            description = "Size ratio between two successive levels of the pyramid. 2 means each level is twice smaller in X and Y than the previous one.")
    int downscaling = 2;

    @Parameter(label = "Tile size X (negative: no tiling)",
            description = "Width of the tiles, in pixels. 512 or 1024 are good values for a pyramidal file; a negative value writes untiled (strip based) planes.")
    int tile_size_x = 512;

    @Parameter(label = "Tile size Y (negative: no tiling)",
            description = "Height of the tiles, in pixels. Usually kept equal to the tile size X.")
    int tile_size_y = 512;

    @Parameter(label = "Number of threads (0 = serial)",
            description = "Number of threads computing and compressing tiles. Writing to the file always happens in a single thread. A good default is the number of cores minus one.")
    int n_threads = 8;

    //@Parameter(label = "Number of tiles computed in advance")


    @Parameter(label = "Compression type", choices = {"LZW", "Uncompressed", "JPEG-2000", "JPEG-2000 Lossy", "JPEG"},
            description = "LZW: lossless, the safe default. Uncompressed: fastest to write, biggest files. " +
                    "JPEG-2000: lossless but slow. JPEG-2000 Lossy and JPEG: smallest files, but pixel values are modified (JPEG only handles 8-bit and RGB images).")
    String compression = "LZW";

    @Parameter
    TaskService taskService;

    @Parameter
    LogService logger;

    @Override
    public void run() {
        final Unit<Length> lUnit;

        switch (unit) {
            case "MILLIMETER": lUnit = UNITS.MILLIMETER; break;
            case "MICROMETER": lUnit = UNITS.MICROMETER; break;
            default: logger.error("Unknown unit: "+unit);
            System.err.println("Unknown unit: "+unit);
            return;
        }

        Instant start = Instant.now();

        List<SourceAndConverter> sources = Arrays.asList(this.sources);

        this.sources = sources.toArray(new SourceAndConverter[0]);

        int max_tiles_queue = 256;

        String imageName = FilenameUtils.removeExtension(file.getName());
        if (imageName.endsWith(".ome")) {
            imageName = FilenameUtils.removeExtension(imageName);
        }

        try {
            OMETiffExporter.OMETiffExporterBuilder.MetaData.MetaDataBuilder builder = OMETiffExporter.builder()
                    .put(this.sources)
                    .defineMetaData(FilenameUtils.removeExtension(imageName))
                    .putMetadataFromSources(this.sources, lUnit);

            if (override_voxel_size) {
                builder.voxelPhysicalSizeMicrometer(this.vox_size_xy_um, this.vox_size_xy_um, this.vox_size_z_um);
            }
            builder.defineWriteOptions()
                    .maxTilesInQueue(max_tiles_queue)
                    .compression(compression)
                    .nThreads(n_threads)
                    .downsample(downscaling)
                    .nResolutionLevels(n_resolution_levels)
                    .rangeT(range_frames)
                    .rangeC(range_channels)
                    .rangeZ(range_slices)
                    .monitor(taskService)
                    .savePath(file.getAbsolutePath())
                    .tileSize(tile_size_x, tile_size_y).create().export();

            KheopsHelper.writeElapsedTime(start,
                    (str) -> logger.subLogger(this.getClass().getSimpleName()).info(str),
                    file.getName()+" export time:");
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

}
