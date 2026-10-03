package net.kdt.pojavlaunch.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Null-safe accessors for the loosely typed JSON the mod APIs return. */
final class Json {
    private Json() {}

    static String str(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        return element.getAsString();
    }

    static String str(JsonObject object, String key, String fallback) {
        String value = str(object, key);
        return value == null ? fallback : value;
    }

    static long num(JsonObject object, String key, long fallback) {
        JsonElement element = object == null ? null : object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsLong();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static boolean bool(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return false;
        return element.getAsBoolean();
    }

    static JsonArray arr(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    static JsonObject obj(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }
}
