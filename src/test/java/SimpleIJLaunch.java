/*-
 * #%L
 * IJ2 commands that use bio-formats to create pyramidal ome.tiff
 * %%
 * Copyright (C) 2018 - 2025 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP)
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
import net.imagej.ImageJ;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;

public class SimpleIJLaunch {


    public static void main(String... args) {
        // Arrange
        // create the ImageJ application context with all available services

        final ImageJ ij = new ImageJ();
        try {
            SwingUtilities.invokeAndWait(() -> ij.ui().showUI());
        } catch (InterruptedException | InvocationTargetException e) {
            e.printStackTrace();
        }
    }
}
