package comp3011;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.junit.Test;

public class PerformanceTest {
    @Test
    public void testPerformance() throws Exception {
        List<Frame> frames = new ArrayList<>();
        InfoVideo videoInfo;
        // Load frames from the test video
        try (FFmpegFrameGrabber grabber =new FFmpegFrameGrabber(new File("src/test/resources/performance.mp4"))) {
            grabber.start();
            Frame frame = grabber.grabImage();
            videoInfo = new InfoVideo(
                    "performance",
                    grabber.getLengthInVideoFrames(),
                    frame.imageWidth,
                    frame.imageHeight,
                    frame.imageDepth,
                    frame.imageChannels,
                    frame.imageStride,
                    grabber.getFrameRate(),
                    (int) Math.round(grabber.getFrameRate()),
                    (int) Math.round(1_000_000 / grabber.getFrameRate()),
                    grabber.getPixelFormat());
            while (frame != null && frames.size() < 50) {
                if (frame.image != null) {
                    frames.add(frame.clone());
                }
                frame = grabber.grabImage();
            }
        }
        // Check that frames were loaded
        assertTrue(frames.size() > 0);
        // Process frames using one thread
        FrameBlackAndWhiter singleProcessor =new FrameBlackAndWhiter();
        singleProcessor.initialise(videoInfo);
        long singleStart = System.nanoTime();
        for (Frame frame : frames) {
            try (Frame copy = frame.clone()) {
                singleProcessor.process(copy, new InfoFrame(0, 0));
            }
        }
        long singleTime = System.nanoTime() - singleStart;
        // Process frames using multiple threads
        ExecutorService workers = Executors.newFixedThreadPool(Math.max(1,Runtime.getRuntime().availableProcessors() - 1));
        long multiStart = System.nanoTime();
        for (Frame frame : frames) {
            workers.submit(() -> {
                try (Frame copy = frame.clone()) {
                	FrameBlackAndWhiter processor = new FrameBlackAndWhiter();
                    processor.initialise(videoInfo);
                    processor.process(copy, new InfoFrame(0, 0));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        workers.shutdown();
        workers.awaitTermination(1, TimeUnit.MINUTES);
        long multiTime = System.nanoTime() - multiStart;
        System.out.println("Single thread: " + singleTime / 1_000_000 + " ms");
        System.out.println("Multi thread: " + multiTime / 1_000_000 + " ms");
        assertTrue(singleTime > 0);
        assertTrue(multiTime > 0);
        for (Frame frame : frames) {
            frame.close();
        }
    }
}