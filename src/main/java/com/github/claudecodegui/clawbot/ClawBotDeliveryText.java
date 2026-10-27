package com.github.claudecodegui.clawbot;

import java.util.ArrayList;
import java.util.List;

/** Provider-sized parts retain every character and keep UTF-16 surrogate pairs together. */
final class ClawBotDeliveryText {
    private ClawBotDeliveryText() { }

    static List<String> split(String text) {
        var chunks = new ArrayList<String>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(text.length(), start + 4000);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) { end--; }
            chunks.add(text.substring(start, end));
            start = end;
        }
        return List.copyOf(chunks);
    }
}
