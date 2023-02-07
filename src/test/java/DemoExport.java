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
                    .defineData()
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
