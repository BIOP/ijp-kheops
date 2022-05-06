/*-
 * #%L
 * IJ2 command make use of bioformats convert to create pyramidal ome.tiff
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
package ch.epfl.biop.kheops.command.deprecated;

import ij.IJ;
import loci.common.DebugTools;
import loci.formats.*;
import loci.formats.tools.ImageConverter;
import net.imagej.ImageJ;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * This example illustrates how to create an ImageJ 2 {@link Command} plugin.
 * The pom file of this project is customized for the PTBIOP Organization (biop.epfl.ch)
 * <p>
 * The code here is opening the biop website. The command can be tested in the java DummyCommandTest class.
 * </p>
 */

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

    public static Consumer<String> logger = (str) -> IJ.log(str);

    @Override
    public void run() {

        Instant start = Instant.now();
        String fileName = input_path.getName();

        //--------------------

        String fileNameWithOutExt = FilenameUtils.removeExtension(fileName) + ".ome.tiff";
        File output_path;

        Boolean isOutputNull = false;
        if ((output_dir == null) || (output_dir.toString().equals(""))) {
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
        } catch (FormatException e) {
            e.printStackTrace();
        } catch (IOException e) {
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

    /**
     * This main function serves for development purposes.
     * It allows you to run the plugin immediately out of
     * your integrated development environment (IDE).
     *
     * @param args whatever, it's ignored
     * @throws Exception thrown during runtime
     */
    public static void main(final String... args) throws Exception {
        // create the ImageJ application context with all available services
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        ij.command().run(KheopsMainCommand.class, true);
    }


}
