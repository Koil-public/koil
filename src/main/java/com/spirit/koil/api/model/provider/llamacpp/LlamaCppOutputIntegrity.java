package com.spirit.koil.api.model.provider.llamacpp;

import java.util.Locale;

/**
 * Conservative corruption detector for native llama.cpp output.
 *
 * <p>This is intentionally not a semantic judge. It only rejects output patterns that are
 * strong evidence of a broken accelerator/runtime path: replacement/private-use glyphs,
 * forbidden controls, pathological single-glyph/pattern repetition, and canary output that
 * is overwhelmingly non-ASCII/noise despite an ASCII-only deterministic request.</p>
 */
final class LlamaCppOutputIntegrity {
    private LlamaCppOutputIntegrity() {
    }

    static Assessment streamingSample(String value) {
        return assess(value, false, false);
    }

    static Assessment finalOutput(String value) {
        return assess(value, false, true);
    }

    static Assessment canary(String value) {
        return assess(value, true, true);
    }

    private static Assessment assess(String input, boolean asciiCanary, boolean requireContent) {
        String value = input == null ? "" : input;
        if (requireContent && value.isBlank()) {
            return Assessment.failed("empty model output");
        }
        if (value.isEmpty()) {
            return Assessment.ok();
        }

        int codePoints = 0;
        int asciiPrintable = 0;
        int lettersOrDigits = 0;
        int suspicious = 0;
        int longestRun = 0;
        int currentRun = 0;
        int previous = -1;

        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            codePoints++;

            if (cp == 0xFFFD || isPrivateUse(cp)) {
                return Assessment.failed("invalid/private-use glyph emitted by model");
            }
            if (Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t') {
                return Assessment.failed("forbidden control character emitted by model");
            }
            if (cp >= 0x20 && cp <= 0x7e) asciiPrintable++;
            if (Character.isLetterOrDigit(cp)) lettersOrDigits++;
            if (!Character.isWhitespace(cp) && !Character.isLetterOrDigit(cp) && cp > 0x7e) suspicious++;

            if (cp == previous && !Character.isWhitespace(cp)) {
                currentRun++;
            } else {
                currentRun = 1;
                previous = cp;
            }
            longestRun = Math.max(longestRun, currentRun);
        }

        if (longestRun >= 18) {
            return Assessment.failed("pathological repeated-glyph output");
        }
        if (repeatingPattern(value)) {
            return Assessment.failed("pathological repeated-token pattern");
        }
        if (asciiCanary && codePoints >= 6) {
            double asciiRatio = asciiPrintable / (double)Math.max(1, codePoints);
            double semanticRatio = lettersOrDigits / (double)Math.max(1, codePoints);
            if (asciiRatio < 0.78D || semanticRatio < 0.28D || suspicious > Math.max(2, codePoints / 10)) {
                return Assessment.failed("accelerator canary returned non-ASCII/noise-heavy output");
            }
        }
        return Assessment.ok();
    }

    private static boolean isPrivateUse(int cp) {
        return (cp >= 0xE000 && cp <= 0xF8FF)
                || (cp >= 0xF0000 && cp <= 0xFFFFD)
                || (cp >= 0x100000 && cp <= 0x10FFFD);
    }

    private static boolean repeatingPattern(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.length() < 32) return false;
        int window = Math.min(256, value.length());
        String sample = value.substring(0, window).toLowerCase(Locale.ROOT);
        for (int size = 1; size <= 8; size++) {
            if (sample.length() < size * 8) continue;
            String pattern = sample.substring(0, size);
            int matched = 0;
            for (int i = 0; i + size <= sample.length(); i += size) {
                if (sample.regionMatches(i, pattern, 0, size)) matched += size;
            }
            if (matched >= sample.length() * 0.86D) return true;
        }
        return false;
    }

    record Assessment(boolean healthy, String detail) {
        Assessment {
            detail = detail == null ? "" : detail.strip();
        }

        static Assessment ok() {
            return new Assessment(true, "healthy");
        }

        static Assessment failed(String detail) {
            return new Assessment(false, detail);
        }
    }

    static final class IntegrityException extends RuntimeException {
        IntegrityException(String detail) {
            super(detail == null || detail.isBlank() ? "llama.cpp output integrity check failed" : detail);
        }
    }
}
