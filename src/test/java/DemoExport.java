/*-
 * #%L
 * IJ2 commands that use bio-formats to create pyramidal ome.tiff
 * %%
 * Copyright (C) 2018 - 2024 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP)
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
import ch.epfl.biop.kheops.ometiff.OMETiffExporter;
import loci.common.DebugTools;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.view.Views;

public class DemoExport {

    public static void main( String[] args )
    {
        int sizeInPixelX = 512;
        int sizeInPixelY = 512;
        int tileSize = 512;
        final FunctionRandomAccessible<ARGBType> checkerboard = new FunctionRandomAccessible<>(
                2,
                (location, value) -> {
                    value.set(
                            ARGBType.rgba(location.getIntPosition(0), location.getIntPosition(1), location.getIntPosition(0)*0,255)
                    );
                },
                ARGBType::new);

        RandomAccessibleInterval<ARGBType> img = Views.interval(checkerboard, new FinalInterval(new long[]{0, 0}, new long[]{sizeInPixelX - 1, sizeInPixelY - 1}));

        DebugTools.setRootLevel("OFF");

        try {
            //String path = "C:\\Users\\nicol\\Desktop\\ometiff\\ntest-" + sizeInPixelX + "x"+sizeInPixelY+"px-" + tileSize + "tile.ome.tiff";
            String path = "C:\\kheops\\ntest-" + sizeInPixelX + "x"+sizeInPixelY+"px-" + tileSize + "tile.ome.tiff";
            System.out.println("Saving "+path);
            OMETiffExporter.builder()
                    .putXYZRAI(img)
                    .putXYZRAI(0,1,img)
                    .defineMetaData("Image")
                    .imageName("Bob")
                    .voxelPhysicalSizeMicrometer(10,10,2)
                    .pixelsTimeIncrementInS(0.35)
                    .defineWriteOptions()
                    .savePath(path)
                    .create()
                    .export();

        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        System.out.println( "done");


    }

}
