import ch.epfl.biop.ij2command.BFConvCommand;
import net.imagej.ImageJ;
import org.junit.Assert;
import org.junit.Test;
import org.scijava.command.CommandModule;
import java.util.concurrent.Future;

public class DummyCommandTest {

    @Test
    public void run() throws Exception {
        // Arrange
        // create the ImageJ application context with all available services
        /*
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();

        // Act
        Future<CommandModule> m = ij.command().run(BFConvCommand.class, true, "input_path","pyramidResolution", 2, "pyramidScale", 2, "tileXsize", 512 , "tileYsize",512);

        // Assert
        Assert.assertEquals(m.get().getOutput("the_answer_to_everything"), 42);*/
    }
}