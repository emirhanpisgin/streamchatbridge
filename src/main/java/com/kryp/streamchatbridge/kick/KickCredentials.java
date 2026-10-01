package com.kryp.streamchatbridge.kick;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kryp.streamchatbridge.util.ConfigPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class KickCredentials {

    private static final Path PATH = ConfigPaths.configDir().resolve("streamchatbridge-kick.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String clientId = "";

    public String clientSecret = "";

    public String accessToken = "";

    public String refreshToken = "";

    public long expiresAt = 0L;

    public static KickCredentials load() {
        if (!Files.exists(PATH)) {
            return new KickCredentials();
        }

        try {
            KickCredentials credentials = GSON.fromJson(Files.readString(PATH), KickCredentials.class);

            if (credentials == null) {
                return new KickCredentials();
            }

            credentials.normalize();

            return credentials;

        } catch (Exception e) {
            System.err.println("[Stream Chat Bridge] Failed to load Kick credentials: " + e.getMessage());

            return new KickCredentials();
        }
    }

    public void save() {
        normalize();

        try {
            Files.createDirectories(PATH.getParent());

            Files.writeString(PATH, GSON.toJson(this), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        } catch (IOException e) {
            System.err.println("[Stream Chat Bridge] Failed to save Kick credentials: " + e.getMessage());
        }
    }

    public void clearTokens() {
        accessToken = "";
        refreshToken = "";
        expiresAt = 0L;

        save();
    }

    public void clearAll() {
        clientId = "";
        clientSecret = "";
        accessToken = "";
        refreshToken = "";
        expiresAt = 0L;

        save();
    }

    public boolean hasClientCredentials() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    public boolean hasAccessToken() {
        return accessToken != null && !accessToken.isBlank();
    }

    public boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    public boolean isAccessTokenExpired() {
        if (expiresAt <= 0L) {
            return true;
        }

        /*
         * Consider the token expired one minute early.
         * This avoids starting an API request with a token
         * that is about to expire.
         */
        return System.currentTimeMillis() >= expiresAt - 60_000L;
    }

    private void normalize() {
        if (clientId == null) {
            clientId = "";
        }

        if (clientSecret == null) {
            clientSecret = "";
        }

        if (accessToken == null) {
            accessToken = "";
        }

        if (refreshToken == null) {
            refreshToken = "";
        }
    }
}