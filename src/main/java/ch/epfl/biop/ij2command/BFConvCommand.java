package ch.epfl.biop.ij2command;

import loci.common.DebugTools;
import loci.formats.FormatException;
import loci.formats.ImageWriter;
import loci.formats.tools.ImageConverter;
import net.imagej.ImageJ;
import org.apache.commons.io.FilenameUtils;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.platform.PlatformService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;

import java.io.File;
import java.io.IOException;

/**
 * This example illustrates how to create an ImageJ 2 {@link Command} plugin.
 * The pom file of this project is customized for the PTBIOP Organization (biop.epfl.ch)
 * <p>
 * The code here is opening the biop website. The command can be tested in the java DummyCommandTest class.
 * </p>
 */

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Kheops - pyramidal OME")
public class BFConvCommand implements Command {

    @Parameter
    UIService uiService;

    @Parameter
    File input_path;

    @Parameter(style = "save", required=false, persist=false)
    File output_path= null;

    @Parameter
    int pyramidResolution=2;

    @Parameter
    int pyramidScale=3;

    @Parameter
    int tileXsize=512;

    @Parameter
    int tileYsize=512;

    @Override
    public void run() {
        uiService.show("Hello from the BIOP!");

        if ((output_path == null) || (output_path.toString().equals("") )){
            String file_name = input_path.getName().toString();
            File parent_dir =  new File( input_path.getParent() );
            File output_dir = new File(parent_dir , "output"  );
            output_dir.mkdirs();
            String fileNameWithOutExt = FilenameUtils.removeExtension( file_name) + ".ome.tiff";
            output_path  = new File(output_dir, fileNameWithOutExt);
        }

        String[] params = {  input_path.toString(), output_path.toString() ,
                            "-pyramid-resolutions",  String.valueOf( pyramidResolution) ,
                            "-pyramid-scale", String.valueOf(pyramidScale),
                            "-tilex", String.valueOf(tileXsize),
                            "-tiley", String.valueOf(tileYsize),
                            "-noflat"
                            } ;

        try {
            DebugTools.enableLogging("INFO");
            ImageConverter converter = new ImageConverter();
            if (!converter.testConvert(new ImageWriter(), params)) System.err.println("Ooups! Something went wrong, contact BIOP team");
            System.out.println("Jobs Done !");
        } catch (FormatException e) {
            e.printStackTrace();
        } catch (IOException e) {
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

        ij.command().run(BFConvCommand.class, true);
    }
}
