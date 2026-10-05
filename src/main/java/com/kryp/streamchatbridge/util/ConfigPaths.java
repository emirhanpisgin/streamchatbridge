package com.kryp.streamchatbridge.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;

public final class ConfigPaths {

    private ConfigPaths() {
    }

    public static Path configDir() {
        //? if fabric {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        //?} else if forge {
        /*return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
        *///?} else {
        /*return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
        *///?}
    }

    /**
     * Per-user directory for secrets (tokens, client secret). Kept out of the
     * shared {@code config/} folder, which people zip and share in modpacks.
     * Shared between all instances, so a login works everywhere.
     */
    public static Path secretDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");

            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, "streamchatbridge");
            }

            return Path.of(System.getProperty("user.home"), "AppData", "Roaming", "streamchatbridge");
        }

        if (os.contains("mac")) {
            return Path.of(System.getProperty("user.home"), "Library", "Application Support", "streamchatbridge");
        }

        String xdg = System.getenv("XDG_CONFIG_HOME");

        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, "streamchatbridge");
        }

        return Path.of(System.getProperty("user.home"), ".config", "streamchatbridge");
    }

    /**
     * Moves a secret file from the legacy {@code config/} location into the
     * secret dir (newest copy wins) and returns the secret path.
     */
    public static Path migrateSecret(String fileName) {
        Path secret = secretDir().resolve(fileName);

        Path legacy = configDir().resolve(fileName);

        try {
            if (Files.exists(legacy)) {
                Files.createDirectories(secret.getParent());

                if (!Files.exists(secret) || Files.getLastModifiedTime(legacy).compareTo(Files.getLastModifiedTime(secret)) > 0) {
                    Files.move(legacy, secret, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(legacy);
                }
            }
        } catch (IOException ignored) {
        }

        return secret;
    }

    /** Best-effort owner-only directory permissions. */
    public static void secureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);

            if (Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            }
        } catch (Exception ignored) {
        }
    }

    /** Best-effort owner-only file permissions. */
    public static void secureFile(Path file) {
        try {
            if (Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            }
        } catch (Exception ignored) {
        }
    }
}
