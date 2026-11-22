
package comp3011;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.List;

import org.bytedeco.javacv.Frame;

public class FrameSubtitler extends FrameProcessor {
    // Shared subtitle data, safely published to worker threads.
    private static volatile List<SubtitleService.SubtitleSegment> subtitles =
            List.of();
    private static volatile boolean enabled = false;
    // Each worker caches its own rendered subtitle image.
    private String cachedText = "";
    private int cachedWidth = -1;
    private int[] cachedPixels;
    private int stripHeight;
    public static void setSubtitles(
            List<SubtitleService.SubtitleSegment> newSubtitles) {
        subtitles = List.copyOf(newSubtitles);
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    @Override
    public void process(Frame frame, InfoFrame info) {

        if (!enabled || frame.image == null || frame.image.length == 0 || !(frame.image[0] instanceof ByteBuffer pixels)) {
            return;
        }

        if (videoInfo.imageChannels() < 3) {
            return;
        }

        // Find the subtitle matching this frame's timestamp
        double seconds = info.timestampUs() / 1_000_000.0;
        String text = "";

        for (SubtitleService.SubtitleSegment segment : subtitles) {
            if (seconds >= segment.start() && seconds < segment.end()) {
                text = SubtitleFormat.format(segment.text());
                break;
            }
        }

        if (text.isBlank()) {
            return;
        }

        int width = videoInfo.imageWidth();
        int height = videoInfo.imageHeight();

        // Render text only when it changes, not on every frame.
        if (!text.equals(cachedText) || width != cachedWidth) {
            renderText(text, width);
        }

        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();
        int startY = Math.max(0, height - stripHeight - 25);

        // Blend white text and black outline into the BGR frame.
        for (int y = 0; y < stripHeight && startY + y < height; y++) {
            for (int x = 0; x < width; x++) {
                int colour = cachedPixels[y * width + x];
                int alpha = (colour >>> 24) & 255;
                if (alpha == 0) {
                    continue;
                }
                int index = (startY + y) * stride + x * channels;
                for (int channel = 0; channel < 3; channel++) {
                    int oldValue = Byte.toUnsignedInt(pixels.get(index + channel));
                    // ByteBuffer uses blue, green, red order.
                    int newValue = (colour >>> (channel * 8)) & 255;
                    int blended = (oldValue * (255 - alpha) + newValue * alpha) / 255;
                    pixels.put(index + channel, (byte) blended);
                }
            }
        }
    }

    // Draw white subtitle text with a black outline.
    private void renderText(String text, int width) {
        cachedText = text;
        cachedWidth = width;

        int fontSize = Math.max(16, Math.min(30, width / 45));
        stripHeight = fontSize * 3 + 12;

        BufferedImage image = new BufferedImage(
                width, stripHeight, BufferedImage.TYPE_INT_ARGB);

        Graphics2D graphics = image.createGraphics();

        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        graphics.setFont(new Font("SansSerif", Font.BOLD, fontSize));

        String[] lines = text.split("\n");

        for (int i = 0; i < lines.length && i < 2; i++) {
            String line = lines[i];

            int textWidth = graphics.getFontMetrics().stringWidth(line);
            int x = Math.max(0, (width - textWidth) / 2);
            int y = fontSize + 5 + i * (fontSize + 6);
            // Black outline.
            graphics.setColor(java.awt.Color.BLACK);
            graphics.drawString(line, x - 2, y);
            graphics.drawString(line, x + 2, y);
            graphics.drawString(line, x, y - 2);
            graphics.drawString(line, x, y + 2);

            // White text.
            graphics.setColor(java.awt.Color.WHITE);
            graphics.drawString(line, x, y);
        }

        graphics.dispose();

        cachedPixels = image.getRGB(
                0, 0, width, stripHeight, null, 0, width);
    }
}
