
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
import ch.epfl.biop.kheops.KheopsHelper;
import ch.epfl.biop.kheops.ometiff.OMETiffExporter;
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
        menuPath = "Plugins>BigDataViewer-Playground>Sources>Export>Export Sources To OME Tiff",
        description = "Saves Bdv sources as a multi-channel OME-Tiff file, keeping original multiresolution levels" +
                " if the sources are initially multiresolution.")
public class KheopsExportSourcesCommand implements Command {

    @Parameter(label = "Sources to export")
    public SourceAndConverter[] sacs;

    @Parameter(label = "Selected Channels. Leave blank for all", required = false)
    String range_channels = "";

    @Parameter(label = "Selected Slices. Leave blank for all", required = false)
    String range_slices = "";

    @Parameter(label = "Selected Timepoints. Leave blank for all",
            required = false)
    String range_frames = "";

    @Parameter(label = "Output file", style = "save")
    public File file;

    @Parameter(label = "Physical unit", choices = { "MILLIMETER", "MICROMETER" })
    String unit;

    @Parameter(label = "Override voxel sizes")
    boolean override_voxel_size;

    @Parameter(label = "Voxel size in micrometer (XY)", style = "format:#.000")
    double vox_size_xy;

    @Parameter(label = "Voxel Z size in micrometer (Z)", style = "format:#.000")
    double vox_size_z;

    @Parameter(label = "Tile Size X (negative: no tiling)")
    int tile_size_x = 512;

    @Parameter(label = "Tile Size Y (negative: no tiling)")
    int tile_size_y = 512;

    @Parameter(label = "Number of threads (0 = serial)")
    int n_threads = 8;

    @Parameter(label = "Number of tiles computed in advance")
    int max_tiles_queue = 256;

    @Parameter(label = "Use LZW compression")
    Boolean lzw_compression = false;

    @Parameter
    TaskService taskService;

    @Parameter
    LogService logger;

    @Override
    public void run() {

        Instant start = Instant.now();

        List<SourceAndConverter> sources = Arrays.asList(sacs);

        sacs = sources.toArray(new SourceAndConverter[0]);

        OMETiffExporter.Builder builder = OMETiffExporter.builder().rangeC(
                range_channels).rangeT(range_frames).rangeZ(range_slices).monitor(
                taskService).savePath(file.getAbsolutePath());

        if (lzw_compression) builder.lzw();
        if (unit.equals("MILLIMETER")) builder.millimeter();
        if (unit.equals("MICROMETER")) builder.micrometer();
        if (override_voxel_size) builder.micrometer().setPixelSize(vox_size_xy,
                vox_size_xy, vox_size_z);
        if ((tile_size_x > 0) && (tile_size_y > 0)) builder.tileSize(tile_size_x,
                tile_size_y);
        builder.maxTilesInQueue(max_tiles_queue);
        builder.nThreads(n_threads);

        try {
            builder.create(sacs).export();

            KheopsHelper.writeElapsedTime(start,
                    logger.subLogger(this.getClass().getSimpleName()),
                    file.getName()+" export time:");
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

}
