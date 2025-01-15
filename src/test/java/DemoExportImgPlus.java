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
import ch.epfl.biop.kheops.ometiff.OMETiffExporter;
import loci.common.DebugTools;
import net.imagej.ImageJ;
import net.imagej.ImgPlus;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.view.Views;
import org.scijava.task.TaskService;

import java.io.File;
import java.time.Duration;
import java.time.Instant;

public class DemoExportImgPlus {

    public static void main( String[] args )
    {
        ImageJ ij = new ImageJ();
        ij.ui().showUI();
        int sizeInPixelX = 512;
        int sizeInPixelY = 512;
        int tileSize = 512;
        int nT = 512;
        final FunctionRandomAccessible<ARGBType> checkerboard = new FunctionRandomAccessible<>(
                2,
                (location, value) -> {
                    value.set(
                            ARGBType.rgba(location.getIntPosition(0), location.getIntPosition(1), location.getIntPosition(0),255)
                    );
                },
                ARGBType::new);

        RandomAccessibleInterval<ARGBType> img = Views.interval(checkerboard, new FinalInterval(new long[]{0, 0}, new long[]{sizeInPixelX - 1, sizeInPixelY - 1}));

        ImgPlus<ARGBType> imgp = ImgPlus.wrapRAI(img);

        Views.hyperSlice(imgp,3,1);
        imgp.axis(0).unit();
        imgp.axis(0).rawValue(1);

        DebugTools.setRootLevel("OFF");

        try {
            //String path = "C:\\Users\\nicol\\Desktop\\ometiff\\ntest-" + sizeInPixelX + "x"+sizeInPixelY+"px-" + tileSize + "tile.ome.tiff";
            String path = "C:\\kheops\\test_x-" + sizeInPixelX + "_y-"+sizeInPixelY+"_tile-" + tileSize + "_nT-"+nT+".ome.tiff";
            System.out.println("Saving "+path);

            Instant start = Instant.now();

            OMETiffExporter.OMETiffExporterBuilder.Data.DataBuilder dataBuilder = OMETiffExporter.builder();

            for (int t = 0;t<nT;t++) {
                dataBuilder.putXYZRAI(0,t,img);
            }

            dataBuilder.defineMetaData("Image")
                    .imageName("Bob")
                    .voxelPhysicalSizeMicrometer(10,10,2)
                    .pixelsTimeIncrementInS(0.35)
                    .defineWriteOptions()
                    .tileSize(tileSize,tileSize)
                    .monitor(ij.get(TaskService.class))
                    .savePath(path)
                    .create()
                    .export();

            Instant end = Instant.now();

            System.out.println("Export time (ms) \t"+Duration.between(start,end).toMillis());

        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        System.out.println( "done");


    }

    public static void saveImgPlus(ImgPlus<?> img, File destination, TaskService ts) {
        try {
            String path = destination.getAbsolutePath();//"C:\\kheops\\test_x-" + sizeInPixelX + "_y-"+sizeInPixelY+"_tile-" + tileSize + "_nT-"+nT+".ome.tiff";
            System.out.println("Saving "+path);

            Instant start = Instant.now();

            int nT = (int) img.dimension(3);

            OMETiffExporter.OMETiffExporterBuilder.Data.DataBuilder dataBuilder = OMETiffExporter.builder();

            for (int t = 0;t<nT;t++) {
                dataBuilder.putXYZRAI(0,t,Views.hyperSlice(img,3,t));
            }

            dataBuilder.defineMetaData(img.getName())
                    .imageName(img.getName())
                    .voxelPhysicalSizeMicrometer(
                            img.axis(0).calibratedValue(1),
                            img.axis(1).calibratedValue(1),
                            img.axis(0).calibratedValue(2))
                    .pixelsTimeIncrementInS(img.axis(0).calibratedValue(3))
                    .defineWriteOptions()
                    .tileSize((int) img.dimension(0),(int) img.dimension(1))
                    .monitor(ts)
                    .savePath(path)
                    .create()
                    .export();

            Instant end = Instant.now();

            System.out.println("Export time (ms) \t"+Duration.between(start,end).toMillis());

        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

}
