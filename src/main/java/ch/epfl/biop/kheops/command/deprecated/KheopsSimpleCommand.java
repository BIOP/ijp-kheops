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
package ch.epfl.biop.kheops.command.deprecated;

import ij.IJ;
import loci.common.DebugTools;
import loci.formats.FormatException;
import loci.formats.ImageWriter;
import loci.formats.tools.ImageConverter;

import loci.formats.ImageReader;
import loci.formats.IFormatReader;

import org.apache.commons.io.FilenameUtils;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.io.IOException;
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
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Kheops>(Deprecated) Kheops - No Param. Convert File to Pyramidal OME  ")
public class KheopsSimpleCommand implements Command {

    @Parameter(label = "Select an input file (required)", style = "open")
    File input_path;

    public static Consumer<String> logger = (str) -> IJ.log(str);

    @Override
    public void run() {

        Instant start = Instant.now();
        String fileName = input_path.getName();

        //--------------------
        int pyramidResolution_max = 6;
        int pyramidScale = 2;
        int tileSize = 512;

        // looking at the image dimensions, will calculate a recommended tileSize
        final IFormatReader reader = new ImageReader();
        try {
            logger.accept( input_path.toString() );
            reader.setId( input_path.toString() );
            int img_width = reader.getSizeX();
            int img_height = reader.getSizeY();
            logger.accept( "width : "+ img_width );

            // about issue on github : https://github.com/BIOP/ijp-kheops/issues/1#issue-507743340
            int smallest = Math.min( img_width, img_height );
            int pyramidResolution = (int) Math.round( (Math.log( smallest)  - Math.log( tileSize ))/ Math.log ( pyramidScale ));
            logger.accept( "pyramidResolution : "+ pyramidResolution );

            // simpler approach, fix to a lim of pyramidResolution_max
            if ( pyramidResolution > pyramidResolution_max) pyramidResolution = pyramidResolution_max ;

            String fileNameWithOutExt = FilenameUtils.removeExtension(fileName) + ".ome.tiff";
            File parent_dir = new File(input_path.getParent());
            File output_path = new File(parent_dir, fileNameWithOutExt);

            String[] params = {input_path.toString(), output_path.toString(),"-overwrite",
                    "-pyramid-resolutions", String.valueOf(pyramidResolution),
                    "-pyramid-scale", String.valueOf(pyramidScale),
                    "-tilex", String.valueOf(tileSize),
                    "-tiley", String.valueOf(tileSize),
                    "-noflat",
                    "-bigtiff",
            };

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

        Instant finish = Instant.now();
        long timeElapsed = Duration.between(start, finish).toMillis();
        logger.accept(input_path.getName()+"\t OME TIFF conversion (Deprecated Kheops Simple Command) \t Run time=\t"+(timeElapsed/1000)+"\t s");

    }

}
