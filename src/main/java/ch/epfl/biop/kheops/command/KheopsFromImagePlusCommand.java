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

import ch.epfl.biop.ImagePlusToOMETiff;
import ch.epfl.biop.kheops.KheopsHelper;
import ij.IJ;
import ij.ImagePlus;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.time.Instant;

@SuppressWarnings("CanBeFinal")
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Kheops>Kheops - Convert Image to Pyramidal OME TIFF")
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
    int pyramidScale=2;

    @Parameter
    LogService logger;

    @Override
    public void run() {

        Instant start = Instant.now();
        String imageTitle = image.getTitle();

        //--------------------

        String fileName = imageTitle + ".ome.tiff";
        File output_path;
        output_dir.mkdirs();
        output_path = new File(output_dir, fileName);

        try {
            ImagePlusToOMETiff.writeToOMETiff(image, output_path, pyramidResolution, pyramidScale, compression);
            KheopsHelper.writeElapsedTime(start,
                    logger.subLogger(this.getClass().getSimpleName()),
                    imageTitle+" export time:");
        } catch (Exception e) {
            IJ.error(e.getMessage());
            e.printStackTrace();
        }
    }

}
