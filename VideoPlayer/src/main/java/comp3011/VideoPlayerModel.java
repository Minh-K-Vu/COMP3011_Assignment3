/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Minh, a1942055
 *
 * Copyright 2026 Simon Ratcliffe
 */

package comp3011;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.HashMap;
import java.util.Map;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * Handles main logic for the video player.
 * 
 * This has functions to control playback, seeking, audio and frame processing.
 * Frame processing uses a multi-thread design to improve performance.
 * JavaFX is used to display the frame.
 * 
 * Each worker thread contains its own FrameProcessor objects. 
 * This means that each thread data is independent and does not share with other threads.
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;

    private final CreateFrameProcessor createFrameProcessor;
    private final int workerCount; // Track number of threads available for frame processing.
    private final ExecutorService frameProcessingExecutor; // Thread pool for thread parallelism
    private final Queue<PendingAudio> pendingAudio = new ArrayDeque<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;
    private final ThreadLocal<WorkerProcessorContext> workerProcessorContext; //Processor chain for each worker
    
    private final Map<Integer, ProcessedFrame> completedFrames =
            new HashMap<>(); // Stores frames that workers have finished processing.
    private InfoVideo currentVideoInfo; // Information about the video currently being processed.
    private int nextFrameSequence; // Sequence number for submitted frames.
    private int nextDisplaySequence; // Next frame to be displayed.
    private int framesInProgress; // Frames from the current play back generation being processed by workers
    private boolean endOfStreamReached; // True when the end is reached.
    private final int maxFramesInPipeline; // Max frames in pipeline
    
    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private PreparedFrame preparedFrame; // Ours, but is just an Image with some meta-data added.
    private boolean playbackOpen; // True when playback is happening.
    private boolean displayFrameWhilePaused; // Store one frame to be shown after seeking while paused
    private boolean pauseRequested;
    private volatile int playbackGeneration; // Used to reject old frames after seeking or restarting
    private boolean audioOutputEnabled;
    private boolean audioAvailable;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            CreateFrameProcessor createFrameProcessor,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
    	workerCount = Math.max(1, Runtime.getRuntime().availableProcessors() - 2); // Leave 2 core free for JavaFX and playback.
    	frameProcessingExecutor = Executors.newFixedThreadPool(workerCount); // Create worker threads for parallel frame processing.
    	maxFramesInPipeline = workerCount; // Allow enough frames for every worker to stay busy
    	workerProcessorContext = ThreadLocal.withInitial(WorkerProcessorContext::new); // Allocate each worker its own processor chain.
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        this.createFrameProcessor = createFrameProcessor;
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
        //Shut down running threads when player closes
        frameProcessingExecutor.shutdownNow();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        try {
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            if (pauseRequested) {
            	displayFrameWhilePaused = true;
                submitNextVideoFrame();
            } else {
            	displayFrameWhilePaused = false;
                fillFramePipeline();
            }
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackTimer.start();
            fillFramePipeline();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
    	playbackGeneration++; //Mark old frames as old play back generation.
    	clearCompletedFrames(); // Delete completed frames from the previous playback position.
    	nextFrameSequence = 0; // Start ordering again for the new playback position.
    	nextDisplaySequence = 0;
    	framesInProgress = 0;
    	endOfStreamReached = false;
        pendingAudio.clear();
        preparedFrame = null;
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }
    // Releases completed frames that have not yet been displayed.
    private void clearCompletedFrames() {

        for (ProcessedFrame processedFrame : completedFrames.values()) {
            processedFrame.frame().close();
        }

        completedFrames.clear();
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    // Get the processor chain for the current worker
    // Each worker has its own processor objects
    private List<FrameProcessor> getWorkerProcessors(int generation, InfoVideo videoInfo) throws Exception {

        WorkerProcessorContext context = workerProcessorContext.get();
        // A new chain is created for new generation after seeking/restarting
        if (context.generation != generation) {
            context.processors = createFrameProcessor.createProcessors();
            initialiseFrameProcessors(context.processors, videoInfo);
            context.generation = generation;
        }

        return context.processors;
    }
    
    // Called regularly by JavaFX to update audio, frames and pipeline
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }
        writeDueAudio(now);
        // Display while playing or after a paused seek
        if (!pauseRequested || displayFrameWhilePaused) {
            prepareCompletedFrameForDisplay();
            if (preparedFrame != null && (displayFrameWhilePaused || preparedFrame.targetTimeNs() <= now)) {
                displayPreparedFrame(now);
                displayFrameWhilePaused = false;
         }
        }

        if (!pauseRequested) {
            fillFramePipeline();
        }
        if (endOfStreamReached && framesInProgress == 0 && completedFrames.isEmpty() && preparedFrame == null) {
            finishPlayback();
        }
    }

    private void displayPreparedFrame(long now) {
        PreparedFrame frame = preparedFrame;
        preparedFrame = null;
        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (frame.targetTimeNs() - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;
        preparedFrame = null;
        displayFrameWhilePaused = false;
        clearCompletedFrames();
        framesInProgress = 0;
        endOfStreamReached = false;
        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        currentVideoInfo = null;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void processFrame(List<FrameProcessor> processors, Frame frame, InfoFrame info) throws Exception {
        for (FrameProcessor processor : processors) {
            processor.process(frame, info);
        }
    }
    // Creates the information that FrameProcessor objects need to understand the current video.
    private InfoVideo createVideoInfo(Frame frame) {

        return new InfoVideo(
                mediaName(videoFile),
                totalVideoFrames,
                frame.imageWidth,
                frame.imageHeight,
                frame.imageDepth,
                frame.imageChannels,
                frame.imageStride,
                frameRate,
                intFrameRate,
                videoFrameDurationUs,
                grabber.getPixelFormat());
    }
    
    private void processFrameOnWorker(
            Frame frame,
            int sequence,
            int generation,
            int frameNumber,
            long timestampUs,
            long relativeTimestampUs,
            long logicalTimestampUs,
            InfoVideo videoInfo) {

        try {
        	//ignore frames from before a seek
            if (generation != playbackGeneration) {
                frame.close();
                return;
            }
            List<FrameProcessor> processors =
                    getWorkerProcessors(generation, videoInfo);
            InfoFrame info = new InfoFrame(
                    frameNumber,
                    timestampUs);
            // Process the frame on this worker thread
            processFrame(processors, frame, info);
            
            ProcessedFrame processedFrame = new ProcessedFrame(
                    frame,
                    sequence,
                    generation,
                    frameNumber,
                    timestampUs,
                    relativeTimestampUs,
                    logicalTimestampUs,
                    System.nanoTime());
            // Return frame to JavaFx thread
            Platform.runLater(() ->
                    receiveProcessedFrame(processedFrame));

        } catch (Exception e) {
            frame.close();

            Platform.runLater(() ->
                    handleWorkerError(generation, e));
        }
    }
    
    private void receiveProcessedFrame(ProcessedFrame frame) {
    	// Discard stale frames
        if (frame.generation() != playbackGeneration || !playbackOpen) {
            frame.frame().close();

            return;
        }
        framesInProgress--;
        // Store the frames to be displayed in order
        completedFrames.put(
                frame.sequence(),
                frame);
    }
    
    private void handleWorkerError(
            int generation,
            Exception exception) {
        if (generation != playbackGeneration || !playbackOpen) {
            return;
        }

        framesInProgress--;

        handlePlaybackError(exception);
    }
    
    // Reads and submits one video frame to a worker
    private boolean submitNextVideoFrame() throws Exception {
        while (playbackOpen) {
            Frame frame = grabFrame();
            if (frame == null) {
                endOfStreamReached = true;
                return false;
            }
            long timestampUs = grabber.getTimestamp();
            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }
            if (frame.image == null) {
                continue;
            }
            if (currentVideoInfo == null) {
                currentVideoInfo = createVideoInfo(frame);
            }
            if (firstTimestampUs == NO_SEEK_REQUEST) {
                firstTimestampUs = timestampUs;
                playbackStartNs = System.nanoTime();
                if (pauseRequested) {
                    pauseStartedNs = playbackStartNs;
                }
            }
            int frameNumber = grabber.getFrameNumber();
            long relativeTimestampUs =
                    Math.max(0, timestampUs - firstTimestampUs);

            long logicalTimestampUs =
                    logicalPlaybackBaseUs + relativeTimestampUs;
            int sequence = nextFrameSequence++;
            int generation = playbackGeneration;
            Frame workerFrame = frame.clone(); // Assign worker a copy of the frame
            framesInProgress++;
            InfoVideo videoInfo = currentVideoInfo;
            // Send frame to worker pool
            frameProcessingExecutor.submit(() ->
                    processFrameOnWorker(
                            workerFrame,
                            sequence,
                            generation,
                            frameNumber,
                            timestampUs,
                            relativeTimestampUs,
                            logicalTimestampUs,
                            videoInfo));
            return true;
        }

        return false;
    }
    // Keeps the worker threads supplied with frames
    private void fillFramePipeline() {
        if (!playbackOpen || pauseRequested || endOfStreamReached) {
            return;
        }
        // Count frames that are in pipeline
        int framesInPipeline = framesInProgress + completedFrames.size() + (preparedFrame == null ? 0 : 1);
        try {

            while (playbackOpen && !pauseRequested && !endOfStreamReached && framesInPipeline < maxFramesInPipeline) {
                boolean submitted = submitNextVideoFrame();
                if (!submitted) {
                    break;
                }
                framesInPipeline++;
            }

        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }
    // Prepare the next completed frame
    private void prepareCompletedFrameForDisplay() {
        if (preparedFrame != null) {
            return;
        }
        ProcessedFrame processedFrame =
                completedFrames.remove(nextDisplaySequence);

        if (processedFrame == null) {
            return;
        }
        try {
        	// Convert the processed frame for Javafx
            Image image = converter.convert(processedFrame.frame());
            long targetTimeNs = playbackStartNs + processedFrame.relativeTimestampUs() * 1_000L;
            preparedFrame = new PreparedFrame(
                    image,
                    processedFrame.frameNumber(),
                    processedFrame.mediaTimestampUs(),
                    processedFrame.logicalTimestampUs(),
                    targetTimeNs,
                    processedFrame.preparedAtNs());
            nextDisplaySequence++;
        } finally {
        	// End the javacv frame
            processedFrame.frame().close();
        }
    }
    
    private void initialiseFrameProcessors(List<FrameProcessor> processors, InfoVideo videoInfo) throws Exception {
    	
        for (FrameProcessor processor : processors) {
            processor.initialise(videoInfo);
        }
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }
    // Stores processors that is in a worker thread
    private static class WorkerProcessorContext {
        private int generation = -1;
        private List<FrameProcessor> processors = new ArrayList<>();
    }
    
    // Represents a video frame after a worker thread has finished applying all requested frame processors.
    private record ProcessedFrame(
            Frame frame,
            int sequence, // Order of the frame
            int generation, // The generation of the frame
            int frameNumber,
            long mediaTimestampUs,
            long relativeTimestampUs, // Time since start of first frame
            long logicalTimestampUs, // Logical playback position used for seeking.
            long preparedAtNs) {
    }

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs) {
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
    // Function to get timestamps in seconds
    public double getCurrentPlaybackSeconds() {
        return currentTimestampUs / 1_000_000.0;
    }
}
