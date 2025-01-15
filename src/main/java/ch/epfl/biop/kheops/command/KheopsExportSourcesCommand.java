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
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.units.unit.Unit;
import org.apache.commons.io.FilenameUtils;
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
        menuPath = "Plugins>BigDataViewer-Playground>Sources>Export>Export Sources To OME Tiff (build pyramid)",
        description = "Saves Bdv sources as a multi-channel OME-Tiff file, with multi-resolution levels recomputed " +
                "from the highest resolution level")
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
    double vox_size_xy_um;

    @Parameter(label = "Voxel Z size in micrometer (Z)", style = "format:#.000")
    double vox_size_z_um;

    @Parameter(label = "Number of resolution levels")
    int n_resolution_levels = 4;

    @Parameter(label = "Scaling factor between resolution levels")
    int downscaling = 2;

    @Parameter(label = "Tile Size X (negative: no tiling)")
    int tile_size_x = 512;

    @Parameter(label = "Tile Size Y (negative: no tiling)")
    int tile_size_y = 512;

    @Parameter(label = "Number of threads (0 = serial)")
    int n_threads = 8;

    //@Parameter(label = "Number of tiles computed in advance")


    @Parameter(label="Compression type", choices = {"LZW", "Uncompressed", "JPEG-2000", "JPEG-2000 Lossy", "JPEG"})
    String compression = "LZW";

    @Parameter(label="Compress temporary files (save space on drive during pyramid building)")
    boolean compress_temp_files = false;

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

        List<SourceAndConverter> sources = Arrays.asList(sacs);

        sacs = sources.toArray(new SourceAndConverter[0]);

        int max_tiles_queue = 256;

        String imageName = FilenameUtils.removeExtension(file.getName());
        if (imageName.endsWith(".ome")) {
            imageName = FilenameUtils.removeExtension(imageName);
        }

        try {
            OMETiffExporter.OMETiffExporterBuilder.MetaData.MetaDataBuilder builder = OMETiffExporter.builder()
                    .put(sacs)
                    .defineMetaData(FilenameUtils.removeExtension(imageName))
                    .putMetadataFromSources(sacs, lUnit);

            if (override_voxel_size) {
                builder.voxelPhysicalSizeMicrometer(this.vox_size_xy_um, this.vox_size_xy_um, this.vox_size_z_um);
            }
            builder.defineWriteOptions()
                    .maxTilesInQueue(max_tiles_queue)
                    .compression(compression)
                    .compressTemporaryFiles(compress_temp_files)
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
