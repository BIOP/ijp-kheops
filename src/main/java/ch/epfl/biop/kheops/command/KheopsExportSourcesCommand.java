
/*-
 * #%L
 * Commands and function for opening, conversion and easy use of bioformats format into BigDataViewer
 * %%
 * Copyright (C) 2019 - 2022 Nicolas Chiaruttini, BIOP, EPFL
 * %%
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the BIOP nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package ch.epfl.biop.kheops.command;

import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.kheops.ometiff.OMETiffExporter;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.task.TaskService;

import java.io.File;
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

    @Override
    public void run() {

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
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

}
