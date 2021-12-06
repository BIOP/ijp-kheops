package ch.epfl.biop.ij2command;

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
