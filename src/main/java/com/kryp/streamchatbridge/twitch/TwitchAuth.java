package com.kryp.streamchatbridge.twitch;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kryp.streamchatbridge.util.BrowserUtils;
import com.kryp.streamchatbridge.util.ConfigPaths;
import com.kryp.streamchatbridge.util.Threads;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TwitchAuth {

    public static final String CLIENT_ID = "gzexzo5fo7dd7lu7ag121lreasylz7";

    private static final String SCOPES = "user:read:chat user:write:chat";

    private static final String DEVICE_URL = "https://id.twitch.tv/oauth2/device";
    private static final String TOKEN_URL = "https://id.twitch.tv/oauth2/token";
    private static final String VALIDATE_URL = "https://id.twitch.tv/oauth2/validate";
    private static final String USERS_URL = "https://api.twitch.tv/helix/users";

    /** Refresh this long before the access token actually expires. */
    private static final long TOKEN_REFRESH_MARGIN_MS = 5 * 60 * 1000L;

    private static final long TOKEN_MAINTENANCE_INTERVAL_MS = 60 * 60 * 1000L;

    private static final Path TOKEN_PATH = ConfigPaths.configDir().resolve("streamchatbridge-twitch.json");

    private static final Gson GSON = new Gson();

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final AtomicBoolean tokenMaintenanceStarted = new AtomicBoolean(false);

    private String accessToken;
    private String refreshToken;

    private long expiresAtMillis;

    private String userId;
    private String username;

    public boolean restoreSession() {
        if (!Files.exists(TOKEN_PATH)) {
            return false;
        }

        try {
            JsonObject json = GSON.fromJson(Files.readString(TOKEN_PATH), JsonObject.class);

            if (json == null) {
                return false;
            }

            accessToken = getString(json, "accessToken");

            refreshToken = getString(json, "refreshToken");

            if (json.has("expiresAt") && !json.get("expiresAt").isJsonNull()) {
                expiresAtMillis = json.get("expiresAt").getAsLong();
            }

            if (accessToken == null || accessToken.isBlank()) {

                return false;
            }

            if (isTokenExpired() && refreshToken != null && !refreshToken.isBlank()) {
                refreshAccessToken();
            }

            if (loadCurrentUser()) {
                startTokenMaintenance();

                return true;
            }

            if (refreshToken != null && !refreshToken.isBlank() && refreshAccessToken()) {

                boolean restored = loadCurrentUser();

                if (restored) {
                    startTokenMaintenance();
                }

                return restored;
            }

        } catch (Exception e) {
            System.err.println("[Stream Chat Bridge] Could not restore Twitch session: " + e.getMessage());
        }

        return false;
    }

    public boolean authenticate() {
        try {
            JsonObject device = requestDeviceCode();

            String deviceCode = device.get("device_code").getAsString();

            String userCode = device.get("user_code").getAsString();

            String verificationUri = device.get("verification_uri").getAsString();

            int expiresIn = device.get("expires_in").getAsInt();

            int interval = device.get("interval").getAsInt();

            System.out.println("[Stream Chat Bridge] Twitch authorization code: " + userCode);

            System.out.println("[Stream Chat Bridge] Open: " + verificationUri);

            if (!BrowserUtils.open(verificationUri)) {
                System.out.println("[Stream Chat Bridge] Open the Twitch authorization URL manually.");
            }

            long deadline = System.currentTimeMillis() + expiresIn * 1000L;

            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(interval * 1000L);

                JsonObject tokenResponse = requestDeviceToken(deviceCode);

                if (tokenResponse.has("access_token")) {
                    accessToken = tokenResponse.get("access_token").getAsString();

                    refreshToken = tokenResponse.get("refresh_token").getAsString();

                    if (tokenResponse.has("expires_in")) {
                        expiresAtMillis = System.currentTimeMillis() + tokenResponse.get("expires_in").getAsLong() * 1000L;
                    }

                    saveTokens();

                    boolean authenticated = loadCurrentUser();

                    if (authenticated) {
                        startTokenMaintenance();
                    }

                    return authenticated;
                }

                String message = getString(tokenResponse, "message");

                if ("authorization_pending".equals(message)) {
                    continue;
                }

                throw new IOException(message != null ? message : "Twitch authentication failed");
            }

            throw new IOException("Twitch authorization expired");

        } catch (Exception e) {
            System.err.println("[Stream Chat Bridge] Twitch authentication failed: " + e.getMessage());

            return false;
        }
    }

    public void logout() {
        accessToken = null;
        refreshToken = null;
        expiresAtMillis = 0;
        userId = null;
        username = null;

        try {
            Files.deleteIfExists(TOKEN_PATH);
        } catch (IOException e) {
            System.err.println("[Stream Chat Bridge] Could not delete Twitch credentials: " + e.getMessage());
        }
    }

    private JsonObject requestDeviceCode() throws IOException, InterruptedException {

        String body = "client_id=" + encode(CLIENT_ID) + "&scopes=" + encode(SCOPES);

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(DEVICE_URL)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Device authorization returned HTTP " + response.statusCode() + ": " + response.body());
        }

        return GSON.fromJson(response.body(), JsonObject.class);
    }

    private JsonObject requestDeviceToken(String deviceCode) throws IOException, InterruptedException {

        String body = "client_id=" + encode(CLIENT_ID) + "&scopes=" + encode(SCOPES) + "&device_code=" + encode(deviceCode) + "&grant_type=" + encode("urn:ietf:params:oauth:grant-type:device_code");

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(TOKEN_URL)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        return GSON.fromJson(response.body(), JsonObject.class);
    }

    private boolean refreshAccessToken() {
        try {
            String body = "client_id=" + encode(CLIENT_ID) + "&grant_type=refresh_token" + "&refresh_token=" + encode(refreshToken);

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(TOKEN_URL)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return false;
            }

            JsonObject json = GSON.fromJson(response.body(), JsonObject.class);

            accessToken = json.get("access_token").getAsString();

            if (json.has("refresh_token")) {
                refreshToken = json.get("refresh_token").getAsString();
            }

            if (json.has("expires_in")) {
                expiresAtMillis = System.currentTimeMillis() + json.get("expires_in").getAsLong() * 1000L;
            }

            saveTokens();

            return true;

        } catch (Exception e) {
            System.err.println("[Stream Chat Bridge] Twitch token refresh failed: " + e.getMessage());

            return false;
        }
    }

    private boolean loadCurrentUser() {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(USERS_URL)).header("Authorization", "Bearer " + accessToken).header("Client-Id", CLIENT_ID).GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return false;
            }

            JsonObject json = GSON.fromJson(response.body(), JsonObject.class);

            if (!json.has("data") || json.getAsJsonArray("data").size() == 0) {

                return false;
            }

            JsonObject user = json.getAsJsonArray("data").get(0).getAsJsonObject();

            userId = user.get("id").getAsString();

            username = user.get("display_name").getAsString();

            return true;

        } catch (Exception e) {
            System.err.println("[Stream Chat Bridge] Twitch user lookup failed: " + e.getMessage());

            return false;
        }
    }

    private void saveTokens() throws IOException {

        JsonObject json = new JsonObject();

        json.addProperty("accessToken", accessToken);

        json.addProperty("refreshToken", refreshToken);

        json.addProperty("expiresAt", expiresAtMillis);

        Files.createDirectories(TOKEN_PATH.getParent());

        Files.writeString(TOKEN_PATH, GSON.toJson(json), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Refreshes the token when it is close to expiry. Safe to call before any
     * authenticated request; returns false when no usable token can be produced.
     */
    public synchronized boolean ensureValidToken() {
        if (!isAuthenticated()) {
            return false;
        }

        if (!isTokenExpired()) {
            return true;
        }

        if (refreshToken == null || refreshToken.isBlank()) {
            return false;
        }

        return refreshAccessToken();
    }

    /**
     * Calls Twitch's validation endpoint (required hourly) and refreshes the token
     * when it is no longer accepted. Network failures keep the current session.
     */
    public synchronized boolean validateToken() {
        if (accessToken == null || accessToken.isBlank()) {
            return false;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(VALIDATE_URL)).header("Authorization", "OAuth " + accessToken).GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {

                JsonObject json = GSON.fromJson(response.body(), JsonObject.class);

                if (json != null && json.has("expires_in")) {
                    expiresAtMillis = System.currentTimeMillis() + json.get("expires_in").getAsLong() * 1000L;

                    saveTokens();
                }

                return true;
            }

            if (response.statusCode() == 401) {
                return refreshAccessToken();
            }

            return true;

        } catch (Exception e) {
            return true;
        }
    }

    private boolean isTokenExpired() {
        return expiresAtMillis <= 0 || System.currentTimeMillis() >= expiresAtMillis - TOKEN_REFRESH_MARGIN_MS;
    }

    private void startTokenMaintenance() {
        if (!tokenMaintenanceStarted.compareAndSet(false, true)) {
            return;
        }

        Threads.start("streamchatbridge-twitch-token", () -> {
            while (true) {
                try {
                    Thread.sleep(TOKEN_MAINTENANCE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }

                try {
                    if (!isAuthenticated()) {
                        continue;
                    }

                    if (isTokenExpired()) {
                        ensureValidToken();
                    }

                    validateToken();
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public String findUserId(String login) {
        if (!isAuthenticated() || login == null || login.isBlank()) {

            return null;
        }

        try {
            String url = USERS_URL + "?login=" + encode(login.trim());

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", "Bearer " + accessToken).header("Client-Id", CLIENT_ID).GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("[Stream Chat Bridge] Twitch channel lookup failed. HTTP " + response.statusCode());

                return null;
            }

            JsonObject json = GSON.fromJson(response.body(), JsonObject.class);

            if (!json.has("data") || json.getAsJsonArray("data").size() == 0) {

                return null;
            }

            return json.getAsJsonArray("data").get(0).getAsJsonObject().get("id").getAsString();

        } catch (Exception e) {
            System.err.println("[Stream Chat Bridge] Twitch channel lookup failed: " + e.getMessage());

            return null;
        }
    }

    private static String getString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {

            return null;
        }

        return object.get(key).getAsString();
    }

    public boolean isAuthenticated() {
        return accessToken != null && userId != null;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }
}