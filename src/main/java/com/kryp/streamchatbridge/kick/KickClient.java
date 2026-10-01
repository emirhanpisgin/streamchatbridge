package com.kryp.streamchatbridge.kick;

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

    private static final Gson GSON = new Gson();

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final KickAuth auth;

    private volatile String channelId;
    private volatile String channelSlug;
    private volatile String broadcasterUserId;

    public KickClient(KickAuth auth) {
        this.auth = auth;
    }

    /*
     * Channel selection
     */

    public boolean setChannel(String channel) {
        if (!auth.isAuthenticated()) {
            System.err.println("[Stream Chat Bridge] Cannot select Kick channel: account is not authenticated.");

            return false;
        }

        String normalized = normalizeChannel(channel);

        if (normalized == null) {
            return loadOwnChannel();
        }

        String accessToken = auth.getValidAccessToken();

        if (accessToken == null) {
            System.err.println("[Stream Chat Bridge] Cannot select Kick channel: no valid access token.");

            return false;
        }

        try {
            String url = CHANNELS_URL + "?slug=" + URLEncoder.encode(normalized, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", "Bearer " + accessToken).header("Accept", "application/json").GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("[Stream Chat Bridge] Kick channel lookup failed. HTTP " + response.statusCode() + ": " + response.body());

                return false;
            }

            JsonObject channelObject = getFirstChannel(response.body());

            if (channelObject == null) {
                System.err.println("[Stream Chat Bridge] Kick channel not found: " + normalized);

                return false;
            }

            if (!applyChannel(channelObject)) {
                return false;
            }

            System.out.println("[Stream Chat Bridge] Kick channel selected: " + channelSlug + " (broadcaster " + broadcasterUserId + ")");

            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return false;

        } catch (Exception e) {
            System.err.println("[Stream Chat Bridge] Kick channel lookup failed: " + e.getMessage());

            return false;
        }
    }

    public boolean loadOwnChannel() {
        if (!auth.isAuthenticated()) {
            System.err.println("[Stream Chat Bridge] Cannot load Kick channel: account is not authenticated.");

            return false;
        }

        String accessToken = auth.getValidAccessToken();

        if (accessToken == null) {
            System.err.println("[Stream Chat Bridge] Cannot load Kick channel: no valid access token.");

            return false;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(CHANNELS_URL)).header("Authorization", "Bearer " + accessToken).header("Accept", "application/json").GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("[Stream Chat Bridge] Kick channel lookup failed. HTTP " + response.statusCode() + ": " + response.body());

                return false;
            }

            JsonObject channelObject = getFirstChannel(response.body());

            if (channelObject == null) {
                System.err.println("[Stream Chat Bridge] Kick account has no channel.");

                return false;
            }

            if (!applyChannel(channelObject)) {
                return false;
            }

            if (channelSlug == null || channelSlug.isBlank()) {

                channelSlug = auth.getUsername();
            }

            System.out.println("[Stream Chat Bridge] Kick channel selected: " + channelSlug + " (broadcaster " + broadcasterUserId + ")");

            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return false;

        } catch (Exception e) {
            System.err.println("[Stream Chat Bridge] Kick channel lookup failed: " + e.getMessage());

            return false;
        }
    }

    private boolean applyChannel(JsonObject channel) {
        String newChannelId = readString(channel, "id");

        String newChannelSlug = readString(channel, "slug");

        String newBroadcasterUserId = readString(channel, "broadcaster_user_id");

        if (newBroadcasterUserId == null || newBroadcasterUserId.isBlank()) {

            System.err.println("[Stream Chat Bridge] Kick channel response did not contain broadcaster_user_id.");

            return false;
        }

        channelId = newChannelId;

        channelSlug = newChannelSlug;

        broadcasterUserId = newBroadcasterUserId;

        return true;
    }

    private static JsonObject getFirstChannel(String responseBody) {
        JsonObject root = GSON.fromJson(responseBody, JsonObject.class);

        if (root == null || !root.has("data") || !root.get("data").isJsonArray()) {

            System.err.println("[Stream Chat Bridge] Kick channel lookup returned an invalid response: " + responseBody);

            return null;
        }

        JsonArray data = root.getAsJsonArray("data");

        if (data.isEmpty()) {
            return null;
        }

        if (!data.get(0).isJsonObject()) {
            return null;
        }

        return data.get(0).getAsJsonObject();
    }

    private static String normalizeChannel(String channel) {
        if (channel == null) {
            return null;
        }

        String normalized = channel.trim();

        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }

        normalized = normalized.trim();

        return normalized.isBlank() ? null : normalized;
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

            System.err.println("[Stream Chat Bridge] Kick message is too long: " + content.length() + "/" + MAX_MESSAGE_LENGTH);

            return false;
        }

        if (!auth.isAuthenticated()) {
            System.err.println("[Stream Chat Bridge] Cannot send Kick message: account is not authenticated.");

            return false;
        }

        if (broadcasterUserId == null) {
            if (!loadOwnChannel()) {
                return false;
            }
        }

        String accessToken = auth.getValidAccessToken();

        if (accessToken == null) {
            System.err.println("[Stream Chat Bridge] Cannot send Kick message: no valid access token.");

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
                System.err.println("[Stream Chat Bridge] Failed to send Kick chat message. HTTP " + response.statusCode() + ": " + response.body());

                return false;
            }

            JsonObject root = GSON.fromJson(response.body(), JsonObject.class);

            if (root == null || !root.has("data") || !root.get("data").isJsonObject()) {

                System.err.println("[Stream Chat Bridge] Kick returned an invalid chat response: " + response.body());

                return false;
            }

            JsonObject data = root.getAsJsonObject("data");

            if (!data.has("is_sent") || data.get("is_sent").isJsonNull()) {

                System.err.println("[Stream Chat Bridge] Kick chat response did not contain is_sent: " + response.body());

                return false;
            }

            boolean sent = data.get("is_sent").getAsBoolean();

            if (!sent) {
                System.err.println("[Stream Chat Bridge] Kick accepted the request but did not send the message: " + response.body());

                return false;
            }

            String messageId = readString(data, "message_id");

            System.out.println("[Stream Chat Bridge] Kick message sent" + (messageId != null ? ": " + messageId : "."));

            return true;

        } catch (NumberFormatException e) {
            System.err.println("[Stream Chat Bridge] Invalid Kick broadcaster user ID: " + broadcasterUserId);

            return false;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return false;

        } catch (Exception e) {
            System.err.println("[Stream Chat Bridge] Failed to send Kick chat message: " + e.getMessage());

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