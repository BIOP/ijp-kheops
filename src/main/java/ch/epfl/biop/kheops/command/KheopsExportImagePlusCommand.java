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

import ch.epfl.biop.ImagePlusToOMETiff;
import ij.ImagePlus;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.task.TaskService;

import java.io.File;

@SuppressWarnings("CanBeFinal")
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Kheops>Kheops - Convert Image to Pyramidal OME TIFF")
public class KheopsExportImagePlusCommand implements Command {

    @Parameter(label = "Image")
    ImagePlus image;

    @Parameter(label= "Output folder (optional)", style = "directory")
    File output_dir;

    @Parameter(label="Compression type", choices = {"LZW", "Uncompressed", "JPEG-2000", "JPEG-2000 Lossy", "JPEG"})
    String compression = "LZW";

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false, required = false)
    String message = "<html><b>Subset in CTZ. Leave fields blank to export all.<br></b>" +
            "You can use commas or colons to separate ranges. eg. '1:2:10' or '1,3,5,8'. '-1' is the last index.</html>";

    @Parameter( label = "Channels subset:", required = false )
    String subset_channels = "";

    @Parameter( label = "Slices subset:", required = false )
    String subset_slices = "";

    @Parameter( label = "Timepoints subset:", required = false )
    String subset_frames = "";

    @Parameter(label="Compress temporary files (LZW)")
    boolean compress_temp_files = false;

    @Parameter
    TaskService taskService;

    @Override
    public void run() {

        if (!output_dir.exists()) output_dir.mkdirs();

        ImagePlusToOMETiff.builder(image, output_dir)
                .compression(compression)
                .taskService(taskService)
                .createMultipleResolutions(true)
                .compressTemporaryFiles(compress_temp_files)
                .rangeT(subset_frames)
                .rangeC(subset_channels)
                .rangeZ(subset_slices)
                .build().execute();
        }

}
