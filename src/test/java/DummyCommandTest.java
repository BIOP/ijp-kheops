import net.imagej.ImageJ;
import org.junit.Test;

public class DummyCommandTest {

    @Test
    public void run() throws Exception {
        // Arrange
        // create the ImageJ application context with all available services

        final ImageJ ij = new ImageJ();
        ij.ui().showUI();

        // Act
        /*Future<CommandModule> m = ij.command().run(KheopsMainCommand.class, true, "input_path","pyramidResolution", 2, "pyramidScale", 2, "tileXsize", 512 , "tileYsize",512);

        // Assert
        Assert.assertEquals(m.get().getOutput("the_answer_to_everything"), 42);*/
    }
}