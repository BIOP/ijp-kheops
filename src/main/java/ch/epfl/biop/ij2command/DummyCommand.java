package ch.epfl.biop.ij2command;

import loci.formats.FormatException;
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

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Dummy Command")
public class DummyCommand implements Command {

    @Parameter
    UIService uiService;

    @Parameter
    PlatformService ps;

    @Parameter
    File input_path;

    @Parameter(type = ItemIO.BOTH , style = "save", required=false)
    File output_path;

    @Parameter
    int pyramidResolution;

    @Parameter
    int pyramidScale;

    @Parameter
    int tileXsize;

    @Parameter
    int tileYsize;

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

        String[] param = {  input_path.toString(), output_path.toString() ,
                            "-pyramid-resolutions",  String.valueOf( pyramidResolution) ,
                            "-pyramid-scale", String.valueOf(pyramidScale),
                            "-tilex", String.valueOf(tileXsize),
                            "-tiley", String.valueOf(tileYsize),
                            "-noflat"
                            } ;

        try {
            ImageConverter.main( param );
        } catch (FormatException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        /*
        try {
            ps.open(new URL("https://biop.epfl.ch"));
        } catch (IOException e) {
            e.printStackTrace();
        }
        the_answer_to_everything = 42;
        */

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
        /*
        String input_path = "C:\\FIJI_201904\\test_img.tif";
        String output_path = "C:\\FIJI_201904\\test_img.ome.tif";
        String[] param = {input_path, output_path ,"-pyramid-resolutions","2", "-pyramid-scale","4", "-tilex","512", "-tiley","512","-noflat" } ;
        ImageConverter.main( param );
        */

        ij.command().run(DummyCommand.class, true);
    }
}
