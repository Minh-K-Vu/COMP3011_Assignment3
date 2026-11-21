package comp3011;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.List;

import org.junit.Test;

public class CommandLineControllerTest {
    @Test
    public void testSingleOption() {
        // Test one frame processor option.
        CommandLineController controller = new CommandLineController(new String[] {"--number-frames", "pom.xml"});
        assertEquals(List.of("n"), controller.getFrameProcessorOptions());
    }

    @Test
    public void testStackedOptions() {
        // Test stacked options keep the correct order.
        CommandLineController controller = new CommandLineController(new String[] {"-nss", "pom.xml"});
        assertEquals(List.of("n", "s", "s"), controller.getFrameProcessorOptions());
    }

    @Test
    public void testInvalidOption() {
        // Test an invalid option stops the program from launching.
        CommandLineController controller =new CommandLineController(new String[] {"--bad-option", "pom.xml"});
        assertEquals(1, controller.getExitCode());
        assertFalse(controller.shouldLaunchApplication());
    }
}