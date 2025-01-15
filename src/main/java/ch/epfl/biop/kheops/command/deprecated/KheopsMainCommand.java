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
package ch.epfl.biop.kheops.command.deprecated;

import ij.IJ;
import loci.common.DebugTools;
import loci.formats.FormatException;
import loci.formats.ImageWriter;
import loci.formats.tools.ImageConverter;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;


import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * SciJava facade of the Bio-Formats {@link ImageConverter}
 */

@SuppressWarnings("CanBeFinal")
@Deprecated
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Kheops>(Deprecated) Kheops - Convert File to Pyramidal OME")
public class KheopsMainCommand implements Command {

    @Parameter(label="Select an input file (required)")
    File input_path;

    @Parameter(label="Specify an output folder (optional)", style = "directory", required=false, persist=false)
    File output_dir;

    @Parameter(label="Pyramid level(s)")
    int pyramidResolution=2;

    @Parameter(label="Pyramid level downsampling factor")
    int pyramidScale=4;

    @Parameter (label="Individual Tile size (in pixel)")
    int tileSize=512;

    @Parameter (label="Save as BIG  ome.tiff?")
    boolean bigtiff=true;

    public static Consumer<String> logger = IJ::log;

    @Override
    public void run() {

        Instant start = Instant.now();
        String fileName = input_path.getName();

        //--------------------

        String fileNameWithOutExt = FilenameUtils.removeExtension(fileName) + ".ome.tiff";
        File output_path;

        boolean isOutputNull = false;
        if ((output_dir == null) || (output_dir.toString().isEmpty())) {
            isOutputNull = true;
            File parent_dir = new File(input_path.getParent());
            output_path = new File(parent_dir, fileNameWithOutExt);

        } else {

            output_dir.mkdirs();
            output_path = new File(output_dir, fileNameWithOutExt);
        }

        String[] params = {input_path.toString(), output_path.toString(), "-overwrite",
                "-pyramid-resolutions", String.valueOf(pyramidResolution),
                "-pyramid-scale", String.valueOf(pyramidScale),
                "-tilex", String.valueOf(tileSize),
                "-tiley", String.valueOf(tileSize),
                "-noflat",
        };

        if (bigtiff) params = ArrayUtils.add( params, "-bigtiff" );

        try {
            DebugTools.enableLogging("INFO");
            ImageConverter converter = new ImageConverter();
            if (!converter.testConvert(new ImageWriter(), params)) {
               logger.accept("Ooups! Something went wrong, contact BIOP team");
            } else {
                logger.accept("Jobs Done !");
            }
        } catch (FormatException | IOException e) {
            e.printStackTrace();
        }

        // workaround for batch
        if (isOutputNull) {
            output_dir = null;
        }

        Instant finish = Instant.now();
        long timeElapsed = Duration.between(start, finish).toMillis();
        logger.accept(input_path.getName()+"\t OME TIFF conversion (Deprecated Kheops Adv. Command) \t Run time=\t"+(timeElapsed/1000)+"\t s");

    }


}
