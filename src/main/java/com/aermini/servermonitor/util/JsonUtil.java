package com.aermini.servermonitor.util;

import com.google.gson.*;

public class JsonUtil {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final JsonParser PARSER = new JsonParser();

    public static Gson gson() {
        return GSON;
    }

    public static JsonObject parse(String json) {
        return PARSER.parse(json).getAsJsonObject();
    }

    public static String toString(JsonElement element) {
        return GSON.toJson(element);
    }

    public static JsonObject newObject() {
        return new JsonObject();
    }

    public static JsonArray newArray() {
        return new JsonArray();
    }
}
