package ch.epfl.biop.ij2command;

import loci.common.DebugTools;
import loci.formats.FormatException;
import loci.formats.ImageWriter;
import loci.formats.tools.ImageConverter;

import loci.formats.ImageReader;
import loci.formats.IFormatReader;

import net.imagej.ImageJ;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * This example illustrates how to create an ImageJ 2 {@link Command} plugin.
 * The pom file of this project is customized for the PTBIOP Organization (biop.epfl.ch)
 * <p>
 * The code here is opening the biop website. The command can be tested in the java DummyCommandTest class.
 * </p>
 */

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Kheops>Kheops - No Param. Convert File to Pyramidal OME  ")
public class KheopsSimpleCommand implements Command {

    @Parameter(label = "Select an input file (required)", style="", required = false)
    File input_path;


    @Override
    public void run() {
        String fileName = input_path.getName();

        //--------------------
        int pyramidResolution_max = 6;
        int pyramidScale = 2;
        int tileSize = 512;

        // looking at the image dimensions, will calculate a recommended tileSize
        final IFormatReader reader = new ImageReader();
        try {
            System.out.println( input_path );
            reader.setId( input_path.toString() );
            int img_width = reader.getSizeX();
            int img_height = reader.getSizeY();
            System.out.println( "width : "+ img_width );

            // about issue on github : https://github.com/BIOP/ijp-kheops/issues/1#issue-507743340
            int smallest = Math.min( img_width, img_height );
            int pyramidResolution = (int) Math.round( (Math.log( smallest)  - Math.log( tileSize ))/ Math.log ( pyramidScale ));
            System.out.println( "pyramidResolution : "+ pyramidResolution );

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
                System.err.println("Ooups! Something went wrong, contact BIOP team");
            } else {
                System.out.println("Jobs Done !");
            }

        } catch (FormatException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

}