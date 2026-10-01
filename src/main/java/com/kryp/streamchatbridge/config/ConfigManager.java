package com.kryp.streamchatbridge.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kryp.streamchatbridge.util.ConfigPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path CONFIG_PATH = ConfigPaths.configDir().resolve("streamchatbridge.json");

    private static ModConfig config;

    private ConfigManager() {
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            config = new ModConfig();

            save();

            return;
        }

        try {
            String json = Files.readString(CONFIG_PATH);

            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            boolean legacyConfig = isLegacyConfig(root);

            config = GSON.fromJson(root, ModConfig.class);

            if (config == null) {
                config = new ModConfig();

                save();

                return;
            }

            if (legacyConfig) {
                migrateLegacyConfig(root);

                save();
            }

        } catch (Exception e) {
            System.err.println("[Stream Chat Bridge] Failed to load config: " + e.getMessage());

            config = new ModConfig();
        }
    }

    private static boolean isLegacyConfig(JsonObject root) {
        return root.has("outgoingPrefix") || root.has("incomingPlatformLabel") || root.has("incomingMessageFormat");
    }

    private static void migrateLegacyConfig(JsonObject root) {
        /*
         * Old shared settings become Twitch settings.
         *
         * Kick receives its own new defaults rather than inheriting
         * Twitch-specific formatting.
         */

        if (root.has("outgoingPrefix") && !root.has("twitchOutgoingPrefix")) {

            String value = getString(root, "outgoingPrefix");

            if (value != null) {
                config.twitchOutgoingPrefix = value;
            }
        }

        if (root.has("incomingPlatformLabel") && !root.has("twitchIncomingPlatformLabel")) {

            String value = getString(root, "incomingPlatformLabel");

            if (value != null && !value.isBlank()) {

                config.twitchIncomingPlatformLabel = value;
            }
        }

        if (root.has("incomingMessageFormat") && !root.has("twitchIncomingMessageFormat")) {

            String value = getString(root, "incomingMessageFormat");

            if (value != null && !value.isBlank()) {

                config.twitchIncomingMessageFormat = value;
            }
        }

        /*
         * Clear legacy fields so Gson no longer writes them
         * with their old values.
         */

        config.outgoingPrefix = null;
        config.incomingPlatformLabel = null;
        config.incomingMessageFormat = null;

        System.out.println("[Stream Chat Bridge] Migrated legacy config to platform-specific settings.");
    }

    private static String getString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {

            return null;
        }

        try {
            return object.get(key).getAsString();

        } catch (Exception ignored) {
            return null;
        }
    }

    public static void save() {
        if (config == null) {
            return;
        }

        try {
            Files.createDirectories(CONFIG_PATH.getParent());

            /*
             * Remove legacy properties from the JSON we actually write.
             */

            JsonObject root = GSON.toJsonTree(config).getAsJsonObject();

            root.remove("outgoingPrefix");
            root.remove("incomingPlatformLabel");
            root.remove("incomingMessageFormat");

            Files.writeString(CONFIG_PATH, GSON.toJson(root));

        } catch (IOException e) {
            System.err.println("[Stream Chat Bridge] Failed to save config: " + e.getMessage());
        }
    }

    public static ModConfig get() {
        if (config == null) {
            config = new ModConfig();
        }

        return config;
    }
}