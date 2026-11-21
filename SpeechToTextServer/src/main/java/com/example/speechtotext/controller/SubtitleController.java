package com.example.speechtotext.controller;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.speechtotext.service.TranscriptionService;

import reactor.core.publisher.Mono;

@RestController
public class SubtitleController {

    private final TranscriptionService transcriptionService;

    public SubtitleController(TranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    // Receive audio and return subtitles with timestamps.
    @PostMapping(value = "/api/v1/subtitles",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> subtitles(
            @RequestPart("file") MultipartFile file) {

        return transcriptionService.transcribeSubtitles(file);
    }

    // Return the total transcription duration in seconds.
    @GetMapping("/api/v1/global/usage")
    public Map<String, Double> usage() {
        return Map.of("usage", transcriptionService.getSubtitleUsage());
    }
}