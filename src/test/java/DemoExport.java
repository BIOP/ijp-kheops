import bdv.BigDataViewer;
import bdv.util.RandomAccessibleIntervalSource;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.kheops.ometiff.OMETiffExporterBuilder;
import ch.epfl.biop.kheops.ometiff.OMETiffPyramidizerExporter;
import ch.epfl.biop.kheops.ometiff.WriterSettings;
import loci.common.DebugTools;
import loci.formats.meta.IMetadata;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.view.Views;

public class DemoExport {

    public static void main( String[] args )
    {
        int sizeInPixelX = 512;
        int sizeInPixelY = 512;
        //int sizeInPixelX = 32;
        //for (int sizeInPixelY = 33;sizeInPixelY<=64;sizeInPixelY++) {
            //int sizeInPixel = 68;
            int tileSize = 512;
            final FunctionRandomAccessible<ARGBType> checkerboard = new FunctionRandomAccessible<>(
                    3,
                    (location, value) -> {
                        value.set(
                                Math.abs(location.getIntPosition(0)) % 10 +
                                        (-Math.abs(location.getIntPosition(1))));// % 10 +Math.abs(location.getIntPosition(2)) % 10 );
                    },
                    ARGBType::new);

            RandomAccessibleInterval<ARGBType> img = Views.interval(checkerboard, new FinalInterval(new long[]{0, 0, 0}, new long[]{sizeInPixelX - 1, sizeInPixelY - 1, 1}));

            //ImageJFunctions.show( img  );

            //BdvFunctions.show(createSourceAndConverter(img));
            //SimpleMultiThreading.threadHaltUnClean();
            //converterSetups.add( BigDataViewer.createConverterSetup( soc, setupId ) );
            //sources.add( soc );

            //SourceAndConverter sac = new SourceAndConverter(null, converter);
            DebugTools.setRootLevel("OFF");

            try {
                //String path = "C:\\Users\\nicol\\Desktop\\ometiff\\ntest-" + sizeInPixelX + "x"+sizeInPixelY+"px-" + tileSize + "tile.ome.tiff";
                String path = "F:\\kheops\\ntest-" + sizeInPixelX + "x"+sizeInPixelY+"px-" + tileSize + "tile.ome.tiff";
                System.out.println("Saving "+path);
                /*OMETiffPyramidizerExporter.builder()
                        .tileSize(tileSize, tileSize) //Math.min(1024,(int)img.dimension(0)), Math.min(256,(int)img.dimension(1)))
                        //.lzw()
                        //.downsample(2)
                        .nResolutionLevels(1)
                        //.monitor(taskService) // Monitor
                        .maxTilesInQueue(60) // Number of blocks computed in advanced, default 10
                        //.savePath("/Users/preibischs/Downloads/test2a.tiff")
                        .savePath(path)
                        .nThreads(7)
                        .micrometer()
                        .create(createSourceAndConverter(img))
                        .export();*/

                OMETiffExporterBuilder.builder()
                        .channelName(0,"Channel_0")
                        .put3DRAI(img)

                        .writeSettings(
                                WriterSettings.builder()
                                        .savePath(path)
                                        .build())
                        .get().export();

            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }



        //}

        System.out.println( "done");


    }

    public static SourceAndConverter<ARGBType> createSourceAndConverter(RandomAccessibleInterval<ARGBType> img )
    {
        if ( img.numDimensions() == 2 )
            img = Views.addDimension( img, 0, 0 );

        final Source< ARGBType > source = new RandomAccessibleIntervalSource<>( img, new ARGBType(), new AffineTransform3D(), "noname" );
        return new SourceAndConverter<>( source, BigDataViewer.createConverterToARGB( new ARGBType() ) );
    }
}
