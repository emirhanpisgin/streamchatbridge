package com.kryp.streamchatbridge.kick;

import com.kryp.streamchatbridge.StreamChatBridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kryp.streamchatbridge.util.Threads;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class KickChatClient {

    public enum ConnectionState {
        CONNECTING, CONNECTED, DISCONNECTED
    }

    private static final String PUSHER_KEY = "32cbd69e4b950bf97679";

    private static final String PUSHER_URL = "wss://ws-us2.pusher.com/app/" + PUSHER_KEY + "?protocol=7&client=streamchatbridge&version=1.0.0&flash=false";

    private static final String CHAT_EVENT = "App\\Events\\ChatMessageEvent";

    private static final long[] RECONNECT_DELAYS_MS = {1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L};

    private static final Gson GSON = new Gson();

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final BiConsumer<String, String> messageHandler;

    private volatile WebSocket webSocket;

    private volatile String channelName;

    private volatile String chatroomId;

    private volatile String username;

    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;

    private volatile Consumer<ConnectionState> stateListener;

    private volatile boolean shouldStayConnected = false;

    private volatile boolean shuttingDown = false;

    private volatile boolean reconnectScheduled = false;

    private volatile int reconnectAttempt = 0;

    private volatile long connectionGeneration = 0;

    public KickChatClient(BiConsumer<String, String> messageHandler) {
        this.messageHandler = messageHandler;
    }

    /*
     * Connection
     */

    public synchronized boolean connect(String username) {
        if (shuttingDown) {
            return false;
        }

        String normalized = normalizeUsername(username);

        if (normalized == null) {
            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Cannot connect to Kick chat: username is missing.");

            return false;
        }

        shouldStayConnected = true;

        this.username = normalized;

        reconnectScheduled = false;

        resetReconnectBackoff();

        connectionGeneration++;

        cleanupConnection();

        return connectInternal(normalized, connectionGeneration);
    }

    private boolean connectInternal(String username, long generation) {
        if (shuttingDown || !shouldStayConnected || generation != connectionGeneration) {

            return false;
        }

        setConnectionState(ConnectionState.CONNECTING);

        String resolvedChatroomId = findChatroomId(username);

        if (generation != connectionGeneration || shuttingDown || !shouldStayConnected) {

            return false;
        }

        if (resolvedChatroomId == null) {
            setConnectionState(ConnectionState.DISCONNECTED);

            scheduleReconnect();

            return false;
        }

        chatroomId = resolvedChatroomId;

        try {
            String resolvedChannelName = "chatrooms." + chatroomId + ".v2";

            channelName = resolvedChannelName;

            httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(PUSHER_URL), new PusherSocketListener(generation))
                    .exceptionally(error -> {
                        handleConnectionFailure(generation, error);

                        return null;
                    });

            StreamChatBridge.LOGGER.info("[Stream Chat Bridge] Listening to Kick chat: " + resolvedChannelName);

            return true;

        } catch (Exception e) {
            if (generation != connectionGeneration) {

                return false;
            }

            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Could not connect to Kick chat: " + e.getMessage());

            cleanupConnection();

            setConnectionState(ConnectionState.DISCONNECTED);

            scheduleReconnect();

            return false;
        }
    }

    private synchronized void handleConnectionFailure(long generation, Throwable error) {
        if (generation != connectionGeneration) {

            return;
        }

        webSocket = null;

        StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick chat connection failed: " + error.getMessage());

        setConnectionState(ConnectionState.DISCONNECTED);

        scheduleReconnect();
    }

    /*
     * Pusher protocol
     */

    private void handlePusherMessage(String raw, long generation) {
        if (raw == null || raw.isBlank()) {

            return;
        }

        JsonObject root = GSON.fromJson(raw, JsonObject.class);

        if (root == null || !root.has("event") || root.get("event").isJsonNull()) {

            return;
        }

        String event = root.get("event").getAsString();

        switch (event) {
            case "pusher:connection_established" -> sendSubscribe();

            case "pusher:ping" -> sendPong();

            case "pusher_internal:subscription_succeeded" -> {
                if (generation != connectionGeneration) {

                    return;
                }

                reconnectScheduled = false;

                resetReconnectBackoff();

                setConnectionState(ConnectionState.CONNECTED);
            }

            case "pusher:error" -> StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick Pusher error: " + root.get("data"));

            case CHAT_EVENT -> {
                JsonElement data = root.get("data");

                if (data != null && data.isJsonPrimitive()) {
                    handleChatEvent(data.getAsString());
                }
            }

            default -> {
            }
        }
    }

    private void sendSubscribe() {
        WebSocket socket = webSocket;

        if (socket == null || channelName == null) {

            return;
        }

        JsonObject data = new JsonObject();

        data.addProperty("channel", channelName);

        JsonObject subscribe = new JsonObject();

        subscribe.addProperty("event", "pusher:subscribe");

        subscribe.add("data", data);

        socket.sendText(GSON.toJson(subscribe), true);
    }

    private void sendPong() {
        WebSocket socket = webSocket;

        if (socket == null) {

            return;
        }

        JsonObject pong = new JsonObject();

        pong.addProperty("event", "pusher:pong");

        pong.add("data", new JsonObject());

        socket.sendText(GSON.toJson(pong), true);
    }

    /*
     * Automatic reconnect
     */

    private synchronized void scheduleReconnect() {
        if (shuttingDown || !shouldStayConnected || reconnectScheduled) {

            return;
        }

        String targetUsername = username;

        if (targetUsername == null || targetUsername.isBlank()) {

            return;
        }

        long delay = RECONNECT_DELAYS_MS[Math.min(reconnectAttempt, RECONNECT_DELAYS_MS.length - 1)];

        reconnectAttempt++;

        reconnectScheduled = true;

        StreamChatBridge.LOGGER.info("[Stream Chat Bridge] Kick connection lost. Reconnecting in " + formatDelay(delay) + "...");

        Threads.start("streamchatbridge-kick-reconnect", () -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                return;
            }

            synchronized (this) {
                if (shuttingDown || !shouldStayConnected) {

                    reconnectScheduled = false;

                    return;
                }

                if (connectionState != ConnectionState.DISCONNECTED) {

                    reconnectScheduled = false;

                    return;
                }

                reconnectScheduled = false;

                connectionGeneration++;

                long generation = connectionGeneration;

                cleanupConnection();

                StreamChatBridge.LOGGER.info("[Stream Chat Bridge] Reconnecting to Kick chat: " + targetUsername);

                connectInternal(targetUsername, generation);
            }
        });
    }

    private synchronized void resetReconnectBackoff() {
        reconnectAttempt = 0;
    }

    /*
     * Manual disconnect
     */

    public synchronized void disconnect() {
        shouldStayConnected = false;

        reconnectScheduled = false;

        resetReconnectBackoff();

        connectionGeneration++;

        cleanupConnection();

        chatroomId = null;

        channelName = null;

        username = null;

        setConnectionState(ConnectionState.DISCONNECTED);
    }

    /*
     * Shutdown
     */

    public synchronized void shutdown() {
        shuttingDown = true;

        shouldStayConnected = false;

        reconnectScheduled = false;

        resetReconnectBackoff();

        connectionGeneration++;

        cleanupConnection();

        chatroomId = null;

        channelName = null;

        username = null;

        setConnectionState(ConnectionState.DISCONNECTED);
    }

    /*
     * Cleanup
     */

    private void cleanupConnection() {
        WebSocket oldSocket = webSocket;

        webSocket = null;

        if (oldSocket != null) {
            try {
                oldSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Disconnected");
            } catch (Exception ignored) {
            }
        }
    }

    /*
     * Chatroom lookup
     */

    private String findChatroomId(String username) {
        try {
            String normalized = normalizeUsername(username);

            if (normalized == null) {
                return null;
            }

            URI uri = URI.create("https://kick.com/api/v2/channels/" + normalized + "/chatroom");

            HttpRequest request = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(10)).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36").header("Referer", "https://kick.com/").GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick chatroom lookup failed. HTTP " + response.statusCode() + ": " + response.body());

                return null;
            }

            JsonObject json = GSON.fromJson(response.body(), JsonObject.class);

            if (json == null || !json.has("id") || json.get("id").isJsonNull()) {

                StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick chatroom lookup returned no ID.");

                return null;
            }

            String id = json.get("id").getAsString();

            StreamChatBridge.LOGGER.info("[Stream Chat Bridge] Kick chatroom ID: " + id);

            return id;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return null;

        } catch (IOException | RuntimeException e) {
            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick chatroom lookup failed: " + e.getMessage());

            return null;
        }
    }

    private static String normalizeUsername(String username) {
        if (username == null) {
            return null;
        }

        String normalized = username.trim();

        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }

        normalized = normalized.trim();

        return normalized.isBlank() ? null : normalized;
    }

    /*
     * Incoming messages
     */

    private void handleChatEvent(String raw) {
        if (raw == null || raw.isBlank()) {

            return;
        }

        try {
            JsonObject data = GSON.fromJson(raw, JsonObject.class);

            if (data == null) {
                return;
            }

            String message = readString(data, "content");

            if (message == null || message.isBlank()) {

                return;
            }

            if (!data.has("sender") || !data.get("sender").isJsonObject()) {

                return;
            }

            JsonObject sender = data.getAsJsonObject("sender");

            String username = readString(sender, "username");

            if (username == null || username.isBlank()) {

                return;
            }

            if (messageHandler != null) {
                messageHandler.accept(username, message);
            }

        } catch (Exception e) {
            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Could not process Kick chat message: " + e.getMessage());
        }
    }

    private static String readString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {

            return null;
        }

        return object.get(key).getAsString();
    }

    /*
     * State
     */

    private void setConnectionState(ConnectionState state) {
        if (connectionState == state) {
            return;
        }

        connectionState = state;

        Consumer<ConnectionState> listener = stateListener;

        if (listener != null) {
            listener.accept(state);
        }
    }

    public ConnectionState getConnectionState() {
        return connectionState;
    }

    public boolean isConnected() {
        return connectionState == ConnectionState.CONNECTED;
    }

    public void setStateListener(Consumer<ConnectionState> listener) {
        stateListener = listener;
    }

    public String getChatroomId() {
        return chatroomId;
    }

    public String getUsername() {
        return username;
    }

    private static String formatDelay(long delayMs) {
        long seconds = delayMs / 1_000L;

        return seconds + (seconds == 1 ? " second" : " seconds");
    }

    private final class PusherSocketListener implements WebSocket.Listener {

        private final long generation;

        private final StringBuilder buffer = new StringBuilder();

        private PusherSocketListener(long generation) {
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket socket) {
            synchronized (KickChatClient.this) {
                if (generation != connectionGeneration || shuttingDown || !shouldStayConnected) {

                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "Stale connection");

                    return;
                }

                webSocket = socket;
            }

            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            if (generation != connectionGeneration) {
                socket.request(1);
                return null;
            }

            buffer.append(data);

            if (last) {
                String message = buffer.toString();

                buffer.setLength(0);

                try {
                    handlePusherMessage(message, generation);
                } catch (Exception e) {
                    StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Failed to handle Kick chat message: " + e.getMessage());
                }
            }

            socket.request(1);

            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            synchronized (KickChatClient.this) {
                if (generation != connectionGeneration) {
                    return null;
                }

                if (webSocket == socket) {
                    webSocket = null;
                }

                StreamChatBridge.LOGGER.info("[Stream Chat Bridge] Kick chat disconnected: " + statusCode + " " + reason);

                setConnectionState(ConnectionState.DISCONNECTED);

                scheduleReconnect();
            }

            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            synchronized (KickChatClient.this) {
                if (generation != connectionGeneration) {
                    return;
                }

                if (webSocket == socket) {
                    webSocket = null;
                }

                StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick chat error: " + error.getMessage());

                setConnectionState(ConnectionState.DISCONNECTED);

                scheduleReconnect();
            }
        }
    }
}
