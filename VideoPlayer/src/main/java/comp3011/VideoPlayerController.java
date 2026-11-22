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
import java.util.function.BiConsumer;

import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import java.util.List;
import javafx.application.Platform;

/**
 * The controller part of the video player model-view-controller architecture.
 * 
 * <p>
 * Wires up events from the {@link VideoPlayerView} to the {@link VideoPlayerModel}.
 * This includes button actions, keyboard shortcuts and display resizing.
 * </p>
 */
public class VideoPlayerController {
    private final VideoPlayerModel model;
    private final VideoPlayerView view;
    // Handles communication with our Spring Boot subtitle API.
    private final SubtitleService subtitleService = new SubtitleService();
    // Subtitles returned by Whisper.
    private List<SubtitleService.SubtitleSegment> subtitles = List.of();
    // Subtitles
    private boolean subtitlesEnabled;
    // Remember the current video so subtitles can be requested later
    private File currentVideoFile;
    // Prevent repeated transcription requests for the same video
    private boolean subtitleRequestStarted;
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    // Add frame processors to constructor
    public VideoPlayerController(
            boolean audioEnabled,
            CreateFrameProcessor createFrameProcessor,
            boolean subtitlesEnabled,
            BiConsumer<Integer, Integer> videoSizeChangedHandler) {
        this.subtitlesEnabled = subtitlesEnabled;
        this.view = new VideoPlayerView();
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        model = new VideoPlayerModel(
                audioEnabled,
                createFrameProcessor,
                this::onVideoSizeChanged,
                this::onFrameReady,
                this::onStatusChanged,
                this::onPlaybackStateChanged,
                this::onAudioOutputStateChanged);

        view.getStartOverButton().setOnAction(_ -> startOver());
        view.getBackFiveButton().setOnAction(_ -> backFiveSeconds());
        view.getPausePlayButton().setOnAction(_ -> togglePause());
        view.getForwardFiveButton().setOnAction(_ -> forwardFiveSeconds());
        view.getStopButton().setOnAction(_ -> stopAndExit());
        view.getAudioButton().setOnAction(_ -> toggleAudioOutput());
        view.getSubtitleButton().setOnAction(_ -> toggleSubtitles());
        view.showSubtitlesEnabled(subtitlesEnabled);
        view.showAudioEnabled(model.isAudioOutputEnabled());
    }

    public void installKeyboardShortcuts(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                stopAndExit();
                event.consume();
            } else if (event.getCode() == KeyCode.SPACE) {
                togglePause();
                event.consume();
            } else if (event.getCode() == KeyCode.LEFT) {
                backFiveSeconds();
                event.consume();
            } else if (event.getCode() == KeyCode.RIGHT) {
                forwardFiveSeconds();
                event.consume();
            } else if (event.getCode() == KeyCode.HOME) {
                startOver();
                event.consume();
            } else if (event.getCode() == KeyCode.A) {
                toggleAudioOutput();
                event.consume();
            } else if (event.getCode() == KeyCode.S) {
                toggleSubtitles();
                event.consume();
            }
        });
    }

    public VideoPlayerView getView() {
        return view;
    }

    public void play(File file) {
        view.setPlaybackControlsDisabled(false);

        currentVideoFile = file;
        subtitleRequestStarted = false;
        subtitles = List.of();

        // Start playing immediately
        model.play(file);

        // -S requests subtitles when playback starts
        if (subtitlesEnabled) {
            loadSubtitles(file);
        }
    }
    
    // Request subtitles in the background.
    private void loadSubtitles(File file) {
        subtitleRequestStarted = true;
        view.setStatus("Generating subtitles...");
        subtitleService.transcribeAsync(file)
                .whenComplete((result, error) -> {
                    Platform.runLater(() -> {
                        // Ignore results if another video has been loaded.
                        if (currentVideoFile != file) {
                            return;
                        }
                        if (error != null) {
                            subtitleRequestStarted = false;
                            view.setStatus("Subtitles unavailable");
                            System.err.println("Subtitle error: " + error.getMessage());
                            return;
                        }
                        subtitles = result;
                        view.setStatus("Subtitles loaded: " + result.size());
                        System.out.println("Loaded " + result.size() + " subtitles");
                    });
                });
    }

    public void shutdown() {
        model.shutdown();
    }

    private void startOver() {
        model.startOver();
    }

    private void backFiveSeconds() {
        model.backFiveSeconds();
    }

    private void togglePause() {
        model.togglePause();
    }

    private void forwardFiveSeconds() {
        model.forwardFiveSeconds();
    }

    private void toggleAudioOutput() {
        model.toggleAudioOutput();
    }
    
    // Turn subtitles on or off
    private void toggleSubtitles() {
        subtitlesEnabled = !subtitlesEnabled;

        view.showSubtitlesEnabled(subtitlesEnabled);

        if (!subtitlesEnabled) {
            // Remove subtitle text immediately.
            view.setSubtitleText("");
        } else if (!subtitleRequestStarted && currentVideoFile != null) {
            // Generate subtitles if the video started without -S.
            loadSubtitles(currentVideoFile);
        }
    }
    
    private void stopAndExit() {
        model.stopPlayback();
        javafx.application.Platform.exit();
    }

    private void onVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void onFrameReady(Image image) {
        view.setImage(image);
        // Clear subtitles when the video frame is removed.
        if (image == null || !subtitlesEnabled) {
            view.setSubtitleText("");
            return;
        }
        // Current playback position in seconds.
        double time = model.getCurrentPlaybackSeconds();
        String subtitleText = "";
        // Find the subtitle that matches the current time.
        for (SubtitleService.SubtitleSegment segment : subtitles) {
            if (time >= segment.start() && time < segment.end()) {
                subtitleText = segment.text();
                break;
            }
        }
        // Show the matching subtitle, or hide it if none matches.
        view.setSubtitleText(
                SubtitleFormat.format(subtitleText)
        );
    }

    private void onStatusChanged(String status) {
        view.setStatus(status);
    }

    private void onPlaybackStateChanged(boolean playing, boolean paused) {
        if (playing && !paused) {
            view.showPauseAction();
        } else {
            view.showPlayAction();
        }
    }

    private void onAudioOutputStateChanged(boolean audioOutputEnabled) {
        view.showAudioEnabled(audioOutputEnabled);
    }
}
