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

import ch.epfl.biop.ImagePlusToOMETiff;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileInfo;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.task.TaskService;

import java.io.File;

@SuppressWarnings("CanBeFinal")
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Kheops>Kheops - Export ImagePlus To OME-TIFF",
        description = "Saves an image already opened in Fiji as a tiled pyramidal OME-TIFF file.")
public class KheopsExportImagePlusCommand implements Command {

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false, required = false)
    String documentation = "<html><body style='width: 620px'><h3>Export an open image to a pyramidal OME-TIFF</h3>" +
            "Writes an image that is <b>already open in Fiji</b> as a tiled, pyramidal <b>.ome.tiff</b>. " +
            "<b>Virtual stacks are supported</b>: slices are read one plane at a time, so the image does not " +
            "need to fit in memory.<br>" +
            "The file is named after the <b>image title</b> (<code>title.ome.tiff</code>), tiled by 512&#215;512, " +
            "and resolution levels are computed by averaging by a factor 2 until the image fits in a single tile.<br>" +
            "The Fiji calibration of the image (voxel size, unit, frame interval) is written into the OME metadata, " +
            "so make sure it is correct before exporting (Image &gt; Properties...).<br>" +
            "<i>An output file that already exists is never overwritten: it is skipped, and a message is " +
            "printed in the Log window.</i></body></html>";

    @Parameter(label = "Image to export",
            description = "The open image to save. Its title gives the name of the output file. Virtual stacks are supported.")
    ImagePlus image;

    @Parameter(label = "Output folder (optional)", style = "directory", required = false,
            description = "Folder where <image title>.ome.tiff is written. Leave empty to write it next to the file the image was opened from. The folder is created if it does not exist.")
    File output_dir;

    @Parameter(label = "Compression type", choices = {"LZW", "Uncompressed", "JPEG-2000", "JPEG-2000 Lossy", "JPEG"},
            description = "LZW: lossless, the safe default. Uncompressed: fastest to write, biggest files. " +
                    "JPEG-2000: lossless but slow. JPEG-2000 Lossy and JPEG: smallest files, but pixel values are modified (JPEG only handles 8-bit and RGB images).")
    String compression = "LZW";

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false, required = false)
    String message = "<html><body style='width: 620px'><b>Select a subset of channels, slices and timepoints. Leave a field blank to export all of them.</b><br>" +
            "Indices start at <b>0</b>, and <b>-1</b> is the last one. Use a comma to list indices and a colon to " +
            "write a range (bounds included):<br>" +
            "<code>0,3,5</code> &#8594; indices 0, 3 and 5 &nbsp;&nbsp; <code>2:6</code> &#8594; 2, 3, 4, 5, 6 " +
            "&nbsp;&nbsp; <code>0:2:10</code> &#8594; 0, 2, 4, 6, 8, 10 (step 2) &nbsp;&nbsp; <code>0:-1</code> &#8594; everything.</body></html>";

    @Parameter( label = "Channels subset", required = false,
            description = "Indices of the channels to export. Blank = all channels." )
    String subset_channels = "";

    @Parameter( label = "Slices subset", required = false,
            description = "Indices of the Z slices to export. Blank = all slices." )
    String subset_slices = "";

    @Parameter( label = "Timepoints subset", required = false,
            description = "Indices of the timepoints to export. Blank = all timepoints." )
    String subset_frames = "";

    @Parameter
    TaskService taskService;

    @Override
    public void run() {

        if ((output_dir == null) || (output_dir.toString().isEmpty())) {
            output_dir = getImageFolder();
            if (output_dir == null) {
                IJ.log("Error: the image " + image.getTitle() + " does not come from a file, " +
                        "please specify an output folder. Export skipped!");
                return;
            }
        }

        if (!output_dir.exists()) output_dir.mkdirs();

        ImagePlusToOMETiff.builder(image, output_dir)
                .compression(compression)
                .taskService(taskService)
                .createMultipleResolutions(true)
                .rangeT(subset_frames)
                .rangeC(subset_channels)
                .rangeZ(subset_slices)
                .build().execute();
        }

    /**
     * @return the folder the image was opened from, or null if the image is not
     *         backed by a file (freshly created image, screen capture, ...)
     */
    private File getImageFolder() {
        FileInfo fi = image.getOriginalFileInfo();
        if ((fi == null) || (fi.directory == null) || (fi.directory.isEmpty())) return null;
        return new File(fi.directory);
    }

}
