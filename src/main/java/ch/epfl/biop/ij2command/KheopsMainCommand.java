package ch.epfl.biop.ij2command;

import loci.common.DebugTools;
import loci.formats.*;
import loci.formats.tools.ImageConverter;
import net.imagej.ImageJ;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.platform.PlatformService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;
import org.scijava.widget.Button;

import java.io.*;
import java.net.URL;

/**
 * This example illustrates how to create an ImageJ 2 {@link Command} plugin.
 * The pom file of this project is customized for the PTBIOP Organization (biop.epfl.ch)
 * <p>
 * The code here is opening the biop website. The command can be tested in the java DummyCommandTest class.
 * </p>
 */

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Kheops>Kheops - Convert File to Pyramidal OME")
public class KheopsMainCommand implements Command {

    @Parameter(label="Select an input file (required)" , required=false)
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

    @Override
    public void run() {
            String fileName = input_path.getName();


            //--------------------

            String fileNameWithOutExt = FilenameUtils.removeExtension(fileName) + ".ome.tiff";
            File output_path = new File("null");

            Boolean isOutputNull = false;
            if ((output_dir == null) || (output_dir.toString().equals(""))) {
                isOutputNull = true;
                File parent_dir = new File(input_path.getParent());
                output_path = new File(parent_dir, fileNameWithOutExt);

            } else {

                output_dir.mkdirs();
                output_path = new File(output_dir, fileNameWithOutExt);
            }

            String[] params = {input_path.toString(), output_path.toString(),
                    "-pyramid-resolutions", String.valueOf(pyramidResolution),
                    "-pyramid-scale", String.valueOf(pyramidScale),
                    "-tilex", String.valueOf(tileSize),
                    "-tiley", String.valueOf(tileSize),
                    "-noflat",
            };

            if (bigtiff) params = ArrayUtils.add( params, "-bigtiff" );

            try {
                DebugTools.enableLogging("INFO");
                if ( isHuygensIcsIds() ) {
                    //-------------------- Patch for ics/ids handling bug : cf https://forum.image.sc/t/trouble-with-converter-testconvert-from-bio-formats-tools/29189/4
                    System.out.println("Applying ICS / IDS Huygens patch, this patch should disappear with the release of BioFormats v > 6.3.0");
                    ImageConverter_IY converter = new ImageConverter_IY();
                    if (!converter.testConvert(new ImageWriter(), params)) {
                        System.err.println("Ooups! Something went wrong, contact BIOP team");
                    } else {
                        System.out.println("Jobs Done !");
                    }
                } else {
                    ImageConverter converter = new ImageConverter();
                    if (!converter.testConvert(new ImageWriter(), params)) {
                        System.err.println("Ooups! Something went wrong, contact BIOP team");
                    } else {
                        System.out.println("Jobs Done !");
                    }
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

    }

    public boolean isHuygensIcsIds() {
        boolean isIcsAndContainsSVILine = false;
        if (FilenameUtils.isExtension(this.input_path.getName(),new String[]{"ics","ids"})) {
            // We have an ics / ids file
            // Let's open the ics File
            String fNamePath = FilenameUtils.removeExtension(input_path.getAbsolutePath())+".ics";
            System.out.println(fNamePath);

            BufferedReader br = null;
            try {
                br = new BufferedReader(new FileReader(fNamePath));
                String line;
                while ((line = br.readLine()) != null) {
                        if (line.contains("history\tsoftware\tSVI-Huygens")) {
                            isIcsAndContainsSVILine = true;
                        }
                }
            } catch (Exception e) {
                return false;
            } finally {
                try {
                    if(br != null)
                        br.close();
                } catch (IOException e) {
                    //
                }
            }
        }
        return isIcsAndContainsSVILine;
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

        ij.command().run(KheopsMainCommand.class, true);
    }


}
