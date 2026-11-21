
package comp3011;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SubtitleService {

    private static final String API_URL =
            "http://localhost:8080/api/v1/subtitles";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    // Store one subtitle with its start and end times.
    public record SubtitleSegment(double start, double end, String text) {
    }

    // Run transcription on a background thread.
    public CompletableFuture<List<SubtitleSegment>> transcribeAsync(
            File videoFile) {

        return CompletableFuture.supplyAsync(() -> {
            Path audioFile = null;

            try {
                // Create a temporary MP3 file.
                audioFile = Files.createTempFile(
                        "subtitle-audio-", ".mp3");

                // Extract audio from the original video.
                Process process = new ProcessBuilder(
                        "ffmpeg",
                        "-y",
                        "-i", videoFile.getAbsolutePath(),
                        "-vn",
                        "-ac", "1",
                        "-ar", "16000",
                        "-b:a", "32k",
                        "-t", "600",
                        audioFile.toString())
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();

                if (process.waitFor() != 0) {
                    throw new RuntimeException(
                            "Could not extract video audio");
                }

                // Build a multipart upload containing the MP3.
                String boundary = "SubtitleBoundary" + System.nanoTime();

                ByteArrayOutputStream body = new ByteArrayOutputStream();

                body.write((
                        "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; " +
                        "name=\"file\"; filename=\"audio.mp3\"\r\n" +
                        "Content-Type: audio/mpeg\r\n\r\n"
                ).getBytes(StandardCharsets.UTF_8));

                body.write(Files.readAllBytes(audioFile));

                body.write((
                        "\r\n--" + boundary + "--\r\n"
                ).getBytes(StandardCharsets.UTF_8));

                // Send the audio to our Spring Boot server.
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(API_URL))
                        .timeout(Duration.ofMinutes(3))
                        .header(
                                "Content-Type",
                                "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(
                                body.toByteArray()))
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    throw new RuntimeException(
                            "Subtitle server returned HTTP "
                            + response.statusCode());
                }

                // Read subtitle segments from the JSON response.
                JsonNode json = mapper.readTree(response.body());
                List<SubtitleSegment> subtitles = new ArrayList<>();

                for (JsonNode segment : json.path("segments")) {
                    double noSpeech = segment.path("no_speech_prob").asDouble();
                    double avgLog = segment.path("avg_logprob").asDouble();

                    // Assignment rule: skip only if BOTH are true.
                    if (noSpeech > 0.6 && avgLog < -1.0) {
                        continue;
                    }

                    double start = segment.path("start").asDouble();
                    double end = segment.path("end").asDouble();
                    String text = segment.path("text").asText().trim();

                    if (!text.isBlank() && end > start) {
                        subtitles.add(
                                new SubtitleSegment(start, end, text));
                    }
                }

                return subtitles;

            } catch (Exception e) {
                throw new RuntimeException(
                        "Subtitle transcription failed", e);

            } finally {
                // Delete the temporary extracted audio.
                if (audioFile != null) {
                    try {
                        Files.deleteIfExists(audioFile);
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }
}
