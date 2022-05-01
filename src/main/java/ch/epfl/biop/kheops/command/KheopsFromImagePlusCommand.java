/*-
 * #%L
 * IJ2 command make use of bioformats convert to create pyramidal ome.tiff
 * %%
 * Copyright (C) 2018 - 2021 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP)
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
import net.imagej.ImageJ;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Kheops>Kheops - Convert Image to Pyramidal OME")
public class KheopsFromImagePlusCommand implements Command {

    @Parameter
    ImagePlus image;

    @Parameter(label="Specify an output folder", style = "directory", persist=false)
    File output_dir;

    @Parameter (label="Compression" , choices = { "Uncompressed", "LZW", "JPEG-2000", "JPEG-2000 Lossy", "JPEG", "zlib" })
    String compression="Uncompressed";

    @Parameter(label="Pyramid level(s)")
    int pyramidResolution=2;

    @Parameter(label="Pyramid level downsampling factor")
    int pyramidScale=4;

    @Override
    public void run() {
        String imageTitle = image.getTitle();

        //--------------------

        String fileName = imageTitle + ".ome.tiff";
        File output_path;
        output_dir.mkdirs();
        output_path = new File(output_dir, fileName);

        try {
            ImagePlusToOMETiff.writeToOMETiff(image, output_path, pyramidResolution, pyramidScale, compression);
        } catch (Exception e) {
            IJ.error(e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * This main function serves for development purposes.
     * It allows you to run the plugin immediately out of
     * your integrated development environment (IDE).
     *
     * @param args whatever, it's ignored
     * @throws Exception
     */
    public static void main(final String... args) throws Exception {
        // create the ImageJ application context with all available services
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
    }
}
