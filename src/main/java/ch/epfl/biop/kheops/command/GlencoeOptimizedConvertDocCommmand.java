package ch.epfl.biop.kheops.command;

import org.scijava.command.Command;
import org.scijava.platform.PlatformService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.IOException;
import java.net.URL;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Kheops>Glencoe Optimized Convert - Read Documentation...")
public class GlencoeOptimizedConvertDocCommmand implements Command {

    @Parameter
    PlatformService ps;

    @Override
    public void run() {

        try {
            // url : go.epfl.ch/ijp-kheops
            ps.open(new URL("https://c4science.ch/w/bioimaging_and_optics_platform_biop/image-processing/qupath/ome-tiff-conversion/"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
