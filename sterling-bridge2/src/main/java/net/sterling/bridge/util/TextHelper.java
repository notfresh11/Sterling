package net.sterling.bridge.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Map;

public class TextHelper {

    public static String replacePlaceholders(String text, Map<String, String> placeholders) {
        if (text == null || placeholders == null) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }

    public static Component formatComponent(String rawMessage, Map<String, String> placeholders) {
        String formatted = replacePlaceholders(rawMessage, placeholders);
        return parseText(formatted);
    }

    public static Component parseText(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }

        String legacy = input.replace('&', '§');
        MutableComponent component = Component.literal(legacy);
        return component;
    }
}
