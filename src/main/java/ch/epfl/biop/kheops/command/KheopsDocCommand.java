/*-
 * #%L
 * IJ2 command make use of bioformats convert to create pyramidal ome.tiff
 * %%
 * Copyright (C) 2018 - 2021 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP)
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
package ch.epfl.biop.kheops.command;

import net.imagej.ImageJ;

import org.scijava.command.Command;
import org.scijava.platform.PlatformService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.IOException;
import java.net.URL;

/**
 * This example illustrates how to create an ImageJ 2 {@link Command} plugin.
 * The pom file of this project is customized for the PTBIOP Organization (biop.epfl.ch)
 * <p>
 * The code here is opening the biop website. The command can be tested in the java DummyCommandTest class.
 * </p>
 */

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Kheops>Kheops - Read Documentation...")
public class KheopsDocCommand implements Command {

    @Parameter
    PlatformService ps;

    @Override
    public void run() {

        try {
            // url : go.epfl.ch/ijp-kheops
            ps.open(new URL("https://github.com/BIOP/ijp-kheops"));
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
     * @throws Exception thrown during runtime
     */
    public static void main(final String... args) throws Exception {
        // create the ImageJ application context with all available services
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();

        ij.command().run(KheopsDocCommand.class, true);
    }


}
