package ch.epfl.biop.ij2command;

import loci.common.DebugTools;
import loci.formats.FormatException;
import loci.formats.ImageWriter;
import loci.formats.tools.ImageConverter;
import net.imagej.ImageJ;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ArrayUtils;
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

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Kheops - Pyramidal OME")
public class BFConvCommand implements Command {

    @Parameter
    UIService uiService;

    @Parameter
    PlatformService ps;

    @Parameter( label = "Read Kheops Documentation", callback = "openDoc", required = false, persist = false )
    private Button chooseFields;

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

    public void openDoc(){
        try {
            // url : go.epfl.ch/ijp-kheops
            ps.open(new URL("https://c4science.ch/w/bioimaging_and_optics_platform_biop/image-processing/imagej_tools/ijp-kheops/"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    @Override
    public void run() {
        //uiService.show("Hello from the BIOP!");

            String fileName = input_path.getName();

            //-------------------- Patch for ics/ids handling bug : cf https://forum.image.sc/t/trouble-with-converter-testconvert-from-bio-formats-tools/29189/4
            patchHuygensIcsIds(false);
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
            if (bigtiff) params = ArrayUtils.add( params, "-bigtiff" );

            try {
                DebugTools.enableLogging("INFO");
                ImageConverter converter = new ImageConverter();
                if (!converter.testConvert(new ImageWriter(), params))
                    System.err.println("Ooups! Something went wrong, contact BIOP team");
                System.out.println("Jobs Done !");
            } catch (FormatException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

            // workaround for batch
            if (isOutputNull) {
                output_dir = null;
            }

            //-------------------- Patch for ics/ids handling bug : cf https://forum.image.sc/t/trouble-with-converter-testconvert-from-bio-formats-tools/29189/4
            patchHuygensIcsIds(true);
            //--------------------

    }

    public void patchHuygensIcsIds(boolean reverse) {
        if (FilenameUtils.isExtension(this.input_path.getName(),new String[]{"ics","ids"})) {
            // We have an ics / ids file
            // Let's open the ics File
            String fNamePath = FilenameUtils.removeExtension(input_path.getAbsolutePath())+".ics";
            System.out.println(fNamePath);

            File f = new File(fNamePath);

            String tmpFileName = FilenameUtils.removeExtension(input_path.getAbsolutePath())+".tmp";

            BufferedReader br = null;
            BufferedWriter bw = null;
            try {
                br = new BufferedReader(new FileReader(fNamePath));
                bw = new BufferedWriter(new FileWriter(tmpFileName));
                String line;
                while ((line = br.readLine()) != null) {
                    if (reverse) {
                        if (line.contains("history\tsoftware\tSDisabledVI-Huygens")) {
                            line = "history\tsoftware\tSVI-Huygens";
                        }
                    } else {
                        if (line.contains("history\tsoftware\tSVI-Huygens")) {
                            line = "history\tsoftware\tSDisabledVI-Huygens";
                        }
                    }
                    bw.write(line+"\n");
                }
            } catch (Exception e) {
                return;
            } finally {
                try {
                    if(br != null)
                        br.close();
                } catch (IOException e) {
                    //
                }
                try {
                    if(bw != null)
                        bw.close();
                } catch (IOException e) {
                    //
                }
            }

            // Once everything is complete, delete old file..
            File oldFile = new File(fNamePath);
            oldFile.delete();

            // And rename tmp file's name to old file name
            File newFile = new File(tmpFileName);
            newFile.renameTo(oldFile);
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
