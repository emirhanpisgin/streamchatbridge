package com.kryp.streamchatbridge.twitch;

import com.kryp.streamchatbridge.StreamChatBridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class TwitchClient {

    private static final String CHAT_MESSAGES_URL = "https://api.twitch.tv/helix/chat/messages";

    private static final String USERS_URL = "https://api.twitch.tv/helix/users";

    private static final int MAX_CHANNEL_LOGIN_LENGTH = 25;

    private static final Gson GSON = new Gson();

    private final TwitchAuth auth;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private String channelId;

    /** Human-readable reason for the last failed channel lookup (shown in the UI). */
    private volatile String lastError;

    public TwitchClient(TwitchAuth auth) {
        this.auth = auth;
    }

    public boolean setChannel(String channel) {
        lastError = null;

        if (!auth.isAuthenticated()) {
            lastError = "Not logged in";

            return false;
        }

        String normalized = normalizeChannelSlug(channel);

        if (normalized.isEmpty()) {
            String ownId = auth.getUserId();

            if (ownId == null || ownId.isBlank()) {
                lastError = "Twitch session expired — log in again in the F8 dashboard";

                return false;
            }

            channelId = ownId;

            StreamChatBridge.LOGGER.info("[Stream Chat Bridge] Twitch channel set to own account");

            return true;
        }

        if (normalized.length() > MAX_CHANNEL_LOGIN_LENGTH) {
            lastError = "Channel name is too long (Twitch allows up to " + MAX_CHANNEL_LOGIN_LENGTH + " characters)";

            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Twitch channel name is too long: " + normalized);

            return false;
        }

        if (!auth.ensureValidToken()) {
            lastError = "Twitch session expired — log in again in the F8 dashboard";

            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Cannot select Twitch channel: no valid access token.");

            return false;
        }

        try {
            String url = USERS_URL + "?login=" + URLEncoder.encode(normalized, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", "Bearer " + auth.getAccessToken()).header("Client-Id", TwitchAuth.CLIENT_ID).header("Accept", "application/json").GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                lastError = response.statusCode() == 401
                        ? "Twitch session expired — log in again in the F8 dashboard"
                        : "Twitch API error (HTTP " + response.statusCode() + ")";

                StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Twitch channel lookup failed. HTTP " + response.statusCode());

                return false;
            }

            JsonObject json = GSON.fromJson(response.body(), JsonObject.class);

            if (json == null || !json.has("data") || !json.get("data").isJsonArray() || json.getAsJsonArray("data").size() == 0) {
                lastError = "Twitch channel not found: " + normalized;

                StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Twitch channel not found: " + normalized);

                return false;
            }

            channelId = json.getAsJsonArray("data").get(0).getAsJsonObject().get("id").getAsString();

            StreamChatBridge.LOGGER.info("[Stream Chat Bridge] Twitch channel set to: " + normalized);

            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return false;

        } catch (Exception e) {
            lastError = "Twitch API unreachable: " + e.getMessage();

            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Twitch channel lookup failed: " + e.getMessage());

            return false;
        }
    }

    /**
     * Accepts a login, a username with {@code @}, or a pasted channel URL
     * ({@code https://twitch.tv/foo}, {@code twitch.tv/foo}, {@code www.twitch.tv/foo}).
     * Returns an empty string when nothing usable is left.
     */
    public static String normalizeChannelSlug(String channel) {
        if (channel == null) {
            return "";
        }

        String normalized = channel.trim();

        if (normalized.isEmpty()) {
            return "";
        }

        normalized = normalized.replaceFirst("(?i)^https?://", "");
        normalized = normalized.replaceFirst("(?i)^(www\\.|m\\.)?twitch\\.tv/?", "");

        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }

        int separator = normalized.indexOf('?');

        if (separator >= 0) {
            normalized = normalized.substring(0, separator);
        }

        separator = normalized.indexOf('#');

        if (separator >= 0) {
            normalized = normalized.substring(0, separator);
        }

        separator = normalized.indexOf('/');

        if (separator >= 0) {
            normalized = normalized.substring(0, separator);
        }

        return normalized.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public boolean sendMessage(String message) {
        if (!auth.isAuthenticated()) {
            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Cannot send Twitch message: not authenticated.");
            return false;
        }

        if (channelId == null) {
            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Cannot send Twitch message: no channel selected.");
            return false;
        }

        if (message == null || message.isBlank()) {
            return false;
        }

        try {
            JsonObject body = new JsonObject();

            body.addProperty("broadcaster_id", channelId);
            body.addProperty("sender_id", auth.getUserId());
            body.addProperty("message", message);

            String jsonBody = GSON.toJson(body);

            HttpResponse<String> response = null;

            for (int attempt = 0; attempt < 2; attempt++) {
                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(CHAT_MESSAGES_URL)).header("Authorization", "Bearer " + auth.getAccessToken()).header("Client-Id", TwitchAuth.CLIENT_ID).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();

                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                // The token can expire mid-session; refresh once and retry.
                if (response.statusCode() == 401 && attempt == 0 && auth.ensureValidToken()) {
                    continue;
                }

                break;
            }

            if (response.statusCode() != 200) {
                StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Failed to send Twitch message. HTTP " + response.statusCode() + ": " + response.body());
                return false;
            }

            JsonObject json = GSON.fromJson(response.body(), JsonObject.class);

            if (json.has("data") && json.getAsJsonArray("data").size() > 0) {

                JsonObject result = json.getAsJsonArray("data").get(0).getAsJsonObject();

                if (result.has("is_sent") && !result.get("is_sent").getAsBoolean()) {

                    String reason = "Unknown reason";

                    if (result.has("drop_reason") && !result.get("drop_reason").isJsonNull()) {

                        JsonObject dropReason = result.getAsJsonObject("drop_reason");

                        if (dropReason.has("message")) {
                            reason = dropReason.get("message").getAsString();
                        }
                    }

                    StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Twitch rejected message: " + reason);

                    return false;
                }
            }

            return true;

        } catch (Exception e) {
            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Failed to send Twitch message: " + e.getMessage());

            return false;
        }
    }

    public String getChannelId() {
        return channelId;
    }

    /** Reason for the last failed lookup, or {@code null} after a successful one. */
    public String getLastError() {
        return lastError;
    }
}