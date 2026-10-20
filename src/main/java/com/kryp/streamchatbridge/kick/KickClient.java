package com.kryp.streamchatbridge.kick;

import com.kryp.streamchatbridge.StreamChatBridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class KickClient {

    private static final String API_URL = "https://api.kick.com/public/v1";

    private static final String CHANNELS_URL = API_URL + "/channels";

    private static final String CHAT_URL = API_URL + "/chat";

    private static final int MAX_MESSAGE_LENGTH = 500;

    private static final int MAX_CHANNEL_SLUG_LENGTH = 25;

    private static final Gson GSON = new Gson();

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final KickAuth auth;

    private volatile String channelId;
    private volatile String channelSlug;
    private volatile String broadcasterUserId;

    /** Human-readable reason for the last failed channel lookup (shown in the UI). */
    private volatile String lastError;

    public KickClient(KickAuth auth) {
        this.auth = auth;
    }

    /*
     * Channel selection
     */

    public boolean setChannel(String channel) {
        lastError = null;

        if (!auth.isAuthenticated()) {
            lastError = "Not logged in";

            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Cannot select Kick channel: account is not authenticated.");

            return false;
        }

        String normalized = normalizeChannelSlug(channel);

        if (normalized.isEmpty()) {
            return loadOwnChannel();
        }

        if (normalized.length() > MAX_CHANNEL_SLUG_LENGTH) {
            lastError = "Channel name is too long (Kick allows up to " + MAX_CHANNEL_SLUG_LENGTH + " characters)";

            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick channel name is too long: " + normalized);

            return false;
        }

        String accessToken = auth.getValidAccessToken();

        if (accessToken == null) {
            lastError = "Kick session expired — log in again in the F8 dashboard";

            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Cannot select Kick channel: no valid access token.");

            return false;
        }

        try {
            String url = CHANNELS_URL + "?slug=" + URLEncoder.encode(normalized, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", "Bearer " + accessToken).header("Accept", "application/json").GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                lastError = "Kick API error (HTTP " + response.statusCode() + ")";

                StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick channel lookup failed. HTTP " + response.statusCode() + ": " + response.body());

                return false;
            }

            JsonObject channelObject = getFirstChannel(response.body());

            if (channelObject == null) {
                lastError = "Kick channel not found: " + normalized;

                StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick channel not found: " + normalized);

                return false;
            }

            if (!applyChannel(channelObject)) {
                return false;
            }

            StreamChatBridge.LOGGER.info("[Stream Chat Bridge] Kick channel selected: " + channelSlug + " (broadcaster " + broadcasterUserId + ")");

            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return false;

        } catch (Exception e) {
            lastError = "Kick API unreachable: " + e.getMessage();

            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick channel lookup failed: " + e.getMessage());

            return false;
        }
    }

    public boolean loadOwnChannel() {
        lastError = null;

        if (!auth.isAuthenticated()) {
            lastError = "Not logged in";

            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Cannot load Kick channel: account is not authenticated.");

            return false;
        }

        String accessToken = auth.getValidAccessToken();

        if (accessToken == null) {
            lastError = "Kick session expired — log in again in the F8 dashboard";

            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Cannot load Kick channel: no valid access token.");

            return false;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(CHANNELS_URL)).header("Authorization", "Bearer " + accessToken).header("Accept", "application/json").GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                lastError = "Kick API error (HTTP " + response.statusCode() + ")";

                StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick channel lookup failed. HTTP " + response.statusCode() + ": " + response.body());

                return false;
            }

            JsonObject channelObject = getFirstChannel(response.body());

            if (channelObject == null) {
                lastError = "This Kick account has no channel";

                StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick account has no channel.");

                return false;
            }

            if (!applyChannel(channelObject)) {
                return false;
            }

            if (channelSlug == null || channelSlug.isBlank()) {

                channelSlug = auth.getUsername();
            }

            StreamChatBridge.LOGGER.info("[Stream Chat Bridge] Kick channel selected: " + channelSlug + " (broadcaster " + broadcasterUserId + ")");

            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return false;

        } catch (Exception e) {
            lastError = "Kick API unreachable: " + e.getMessage();

            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick channel lookup failed: " + e.getMessage());

            return false;
        }
    }

    private boolean applyChannel(JsonObject channel) {
        String newChannelId = readString(channel, "id");

        String newChannelSlug = readString(channel, "slug");

        String newBroadcasterUserId = readString(channel, "broadcaster_user_id");

        if (newBroadcasterUserId == null || newBroadcasterUserId.isBlank()) {
            lastError = "Kick API returned an unexpected channel response";

            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick channel response did not contain broadcaster_user_id.");

            return false;
        }

        channelId = newChannelId;

        channelSlug = newChannelSlug;

        broadcasterUserId = newBroadcasterUserId;

        return true;
    }

    private JsonObject getFirstChannel(String responseBody) {
        JsonObject root = GSON.fromJson(responseBody, JsonObject.class);

        if (root == null || !root.has("data") || !root.get("data").isJsonArray()) {
            lastError = "Kick API returned an unexpected response";

            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick channel lookup returned an invalid response: " + responseBody);

            return null;
        }

        JsonArray data = root.getAsJsonArray("data");

        if (data.size() == 0) {
            return null;
        }

        if (!data.get(0).isJsonObject()) {
            return null;
        }

        return data.get(0).getAsJsonObject();
    }

    /**
     * Accepts a slug, a username with or without {@code @}, or a pasted channel URL
     * ({@code https://kick.com/foo}, {@code kick.com/foo}, {@code www.kick.com/foo}).
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
        normalized = normalized.replaceFirst("(?i)^(www\\.)?kick\\.com/?", "");

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

    /*
     * Chat
     */

    public boolean sendMessage(String message) {
        if (message == null) {
            return false;
        }

        String content = message.trim();

        if (content.isEmpty()) {
            return false;
        }

        if (content.length() > MAX_MESSAGE_LENGTH) {

            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick message is too long: " + content.length() + "/" + MAX_MESSAGE_LENGTH);

            return false;
        }

        if (!auth.isAuthenticated()) {
            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Cannot send Kick message: account is not authenticated.");

            return false;
        }

        if (broadcasterUserId == null) {
            if (!loadOwnChannel()) {
                return false;
            }
        }

        String accessToken = auth.getValidAccessToken();

        if (accessToken == null) {
            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Cannot send Kick message: no valid access token.");

            return false;
        }

        try {
            long broadcasterId = Long.parseLong(broadcasterUserId);

            JsonObject body = new JsonObject();

            body.addProperty("broadcaster_user_id", broadcasterId);

            body.addProperty("content", content);

            body.addProperty("type", "user");

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(CHAT_URL)).header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json").header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Failed to send Kick chat message. HTTP " + response.statusCode() + ": " + response.body());

                return false;
            }

            JsonObject root = GSON.fromJson(response.body(), JsonObject.class);

            if (root == null || !root.has("data") || !root.get("data").isJsonObject()) {

                StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick returned an invalid chat response: " + response.body());

                return false;
            }

            JsonObject data = root.getAsJsonObject("data");

            if (!data.has("is_sent") || data.get("is_sent").isJsonNull()) {

                StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick chat response did not contain is_sent: " + response.body());

                return false;
            }

            boolean sent = data.get("is_sent").getAsBoolean();

            if (!sent) {
                StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick accepted the request but did not send the message: " + response.body());

                return false;
            }

            String messageId = readString(data, "message_id");

            StreamChatBridge.LOGGER.info("[Stream Chat Bridge] Kick message sent" + (messageId != null ? ": " + messageId : "."));

            return true;

        } catch (NumberFormatException e) {
            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Invalid Kick broadcaster user ID: " + broadcasterUserId);

            return false;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return false;

        } catch (Exception e) {
            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Failed to send Kick chat message: " + e.getMessage());

            return false;
        }
    }

    /*
     * State
     */

    public void clearChannel() {
        channelId = null;
        channelSlug = null;
        broadcasterUserId = null;
    }

    /** Reason for the last failed lookup, or {@code null} after a successful one. */
    public String getLastError() {
        return lastError;
    }

    public boolean hasChannel() {
        return broadcasterUserId != null;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getChannelSlug() {
        return channelSlug;
    }

    public String getBroadcasterUserId() {
        return broadcasterUserId;
    }

    private static String readString(JsonObject object, String name) {
        if (!object.has(name) || object.get(name).isJsonNull()) {

            return null;
        }

        return object.get(name).getAsString();
    }
}