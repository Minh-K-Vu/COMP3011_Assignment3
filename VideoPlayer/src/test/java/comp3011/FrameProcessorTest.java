package comp3011;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class FrameProcessorTest {
    @Test
    public void testOrder() {
        // Test processors are created in the same order.
        CreateFrameProcessor creator = new CreateFrameProcessor(List.of("n", "s", "p"));
        List<FrameProcessor> processors = creator.createProcessors();
        assertEquals(3, processors.size());
        assertTrue(processors.get(0) instanceof FrameNumberer);
        assertTrue(processors.get(1) instanceof FrameScratcher);
        assertTrue(processors.get(2) instanceof FramePepperer);
    }
    @Test
    public void testRepeated() {
        // Test the same processor can be added more than once.
        CreateFrameProcessor creator = new CreateFrameProcessor(List.of("n", "n", "s"));
        List<FrameProcessor> processors = creator.createProcessors();
        assertEquals(3, processors.size());
        assertTrue(processors.get(0) instanceof FrameNumberer);
        assertTrue(processors.get(1) instanceof FrameNumberer);
        assertTrue(processors.get(2) instanceof FrameScratcher);
    }
}
