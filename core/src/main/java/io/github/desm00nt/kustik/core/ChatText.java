package io.github.desm00nt.kustik.core;

/** Plain chat only: no Minecraft formatting, controls, bidi overrides or unbounded output. */
public final class ChatText {
    private ChatText() {}

    public static String clean(String input, int maxCodePoints) {
        if (maxCodePoints < 1) {
            throw new IllegalArgumentException("Positive text limit required");
        }
        StringBuilder out = new StringBuilder();
        boolean formattingCode = false;
        boolean space = false;
        for (int cp : input.codePoints().toArray()) {
            if (formattingCode) {
                formattingCode = false;
                continue;
            }
            if (cp == '\u00a7') {
                formattingCode = true;
                continue;
            }
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = !out.isEmpty();
                continue;
            }
            int type = Character.getType(cp);
            if (Character.isISOControl(cp) || type == Character.FORMAT || type == Character.SURROGATE) {
                continue;
            }
            if (space) {
                out.append(' ');
                space = false;
            }
            out.appendCodePoint(cp);
        }
        String result = out.toString();
        if (result.codePointCount(0, result.length()) > maxCodePoints) {
            return result.substring(0, result.offsetByCodePoints(0, maxCodePoints - 1)) + "…";
        }
        return result;
    }
}
