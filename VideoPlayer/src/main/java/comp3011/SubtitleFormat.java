
package comp3011;

public class SubtitleFormat {
    private static final int MAX_LINE_LENGTH = 42;
    private static final int MAX_LINES = 2;
    // Wrap subtitle text into at most two lines.
    public static String format(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] words = text.trim().split("\\s+");
        StringBuilder result = new StringBuilder();
        int lineLength = 0;
        int lineNumber = 1;
        for (String word : words) {
            // Keep each line within 42 characters.
            if (lineLength + word.length()
                    + (lineLength == 0 ? 0 : 1)
                    > MAX_LINE_LENGTH) {
                if (lineNumber >= MAX_LINES) {
                    break;
                }
                result.append("\n");
                lineNumber++;
                lineLength = 0;
            }

            if (lineLength > 0) {
                result.append(" ");
                lineLength++;
            }
            result.append(word);
            lineLength += word.length();
        }

        return result.toString();
    }
}
