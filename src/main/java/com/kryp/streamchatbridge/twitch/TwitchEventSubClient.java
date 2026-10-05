package com.kryp.streamchatbridge.twitch;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kryp.streamchatbridge.util.Threads;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class TwitchEventSubClient {

    public enum ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    private static final String DEFAULT_WEBSOCKET_URL = "wss://eventsub.wss.twitch.tv/ws";

    private static final String SUBSCRIPTIONS_URL = "https://api.twitch.tv/helix/eventsub/subscriptions";

    private static final long[] RECONNECT_DELAYS_MS = {1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L};

    private static final Gson GSON = new Gson();

    private final TwitchAuth auth;

    private final BiConsumer<String, String> messageHandler;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;

    private volatile Consumer<ConnectionState> stateListener;

    private volatile WebSocket webSocket;

    private volatile String channelId;

    private volatile boolean shouldStayConnected = false;

    private volatile boolean shuttingDown = false;

    private volatile boolean reconnectScheduled = false;

    private volatile int reconnectAttempt = 0;

    private volatile long connectionGeneration = 0;

    private volatile long keepaliveTimeoutMs = 10_000L;

    private volatile long lastMessageAt = 0L;

    public TwitchEventSubClient(TwitchAuth auth, BiConsumer<String, String> messageHandler) {
        this.auth = auth;
        this.messageHandler = messageHandler;
    }

    public synchronized void connect() {
        if (shuttingDown) {
            return;
        }

        shouldStayConnected = true;

        if (!canConnect()) {
            shouldStayConnected = false;
            return;
        }

        if (connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.CONNECTED) {
            return;
        }

        resetReconnectBackoff();

        connectTo(DEFAULT_WEBSOCKET_URL, false);
    }

    public synchronized void reconnect() {
        if (shuttingDown) {
            return;
        }

        shouldStayConnected = true;

        if (!canConnect()) {
            shouldStayConnected = false;
            return;
        }

        reconnectScheduled = false;

        resetReconnectBackoff();

        WebSocket oldSocket = webSocket;

        webSocket = null;

        connectionGeneration++;

        setConnectionState(ConnectionState.CONNECTING);

        if (oldSocket != null) {
            oldSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Reconnecting");
        }

        connectTo(DEFAULT_WEBSOCKET_URL, false);
    }

    public synchronized void disconnect() {
        shouldStayConnected = false;
        reconnectScheduled = false;

        resetReconnectBackoff();

        connectionGeneration++;

        WebSocket socket = webSocket;

        webSocket = null;

        setConnectionState(ConnectionState.DISCONNECTED);

        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "Disconnecting");
        }
    }

    public synchronized void shutdown() {
        shuttingDown = true;
        shouldStayConnected = false;
        reconnectScheduled = false;

        resetReconnectBackoff();

        connectionGeneration++;

        WebSocket socket = webSocket;

        webSocket = null;

        setConnectionState(ConnectionState.DISCONNECTED);

        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "Minecraft shutting down");
        }
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public void setStateListener(Consumer<ConnectionState> stateListener) {
        this.stateListener = stateListener;
    }

    public boolean isConnected() {
        return connectionState == ConnectionState.CONNECTED;
    }

    public ConnectionState getConnectionState() {
        return connectionState;
    }

    private boolean canConnect() {
        if (!auth.isAuthenticated()) {
            System.err.println("[Stream Chat Bridge] Cannot connect to Twitch chat: not authenticated.");

            setConnectionState(ConnectionState.DISCONNECTED);

            return false;
        }

        if (channelId == null || channelId.isBlank()) {
            System.err.println("[Stream Chat Bridge] Cannot connect to Twitch chat: no channel selected.");

            setConnectionState(ConnectionState.DISCONNECTED);

            return false;
        }

        return true;
    }

    private synchronized void connectTo(String url, boolean twitchReconnect) {
        if (shuttingDown || !shouldStayConnected) {
            return;
        }

        long generation = ++connectionGeneration;

        reconnectScheduled = false;

        setConnectionState(ConnectionState.CONNECTING);

        if (twitchReconnect) {
            System.out.println("[Stream Chat Bridge] Migrating Twitch EventSub connection...");
        } else {
            System.out.println("[Stream Chat Bridge] Connecting to Twitch EventSub...");
        }

        EventSubSocketListener listener = new EventSubSocketListener(generation, twitchReconnect);

        httpClient.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create(url), listener).exceptionally(error -> {
            handleConnectionFailure(generation, error);

            return null;
        });
    }

    private synchronized void handleConnectionFailure(long generation, Throwable error) {
        if (generation != connectionGeneration) {
            return;
        }

        webSocket = null;

        System.err.println("[Stream Chat Bridge] EventSub connection failed: " + error.getMessage());

        setConnectionState(ConnectionState.DISCONNECTED);

        scheduleReconnect();
    }

    private synchronized void scheduleReconnect() {
        if (shuttingDown || !shouldStayConnected || reconnectScheduled) {
            return;
        }

        long delay = RECONNECT_DELAYS_MS[Math.min(reconnectAttempt, RECONNECT_DELAYS_MS.length - 1)];

        reconnectAttempt++;

        reconnectScheduled = true;

        System.out.println("[Stream Chat Bridge] Twitch connection lost. Reconnecting in " + formatDelay(delay) + "...");

        Threads.start("streamchatbridge-twitch-reconnect", () -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            synchronized (this) {
                reconnectScheduled = false;

                if (shuttingDown || !shouldStayConnected) {
                    return;
                }

                if (connectionState != ConnectionState.DISCONNECTED) {
                    return;
                }

                if (!canConnect()) {
                    return;
                }

                connectTo(DEFAULT_WEBSOCKET_URL, false);
            }
        });
    }

    private synchronized void resetReconnectBackoff() {
        reconnectAttempt = 0;
    }

    private String formatDelay(long delayMs) {
        if (delayMs % 1_000L == 0) {
            long seconds = delayMs / 1_000L;

            return seconds + (seconds == 1 ? " second" : " seconds");
        }

        return delayMs + " ms";
    }

    private synchronized void handleTwitchReconnect(JsonObject root, long generation) {
        if (generation != connectionGeneration || shuttingDown || !shouldStayConnected) {
            return;
        }

        JsonObject payload = root.getAsJsonObject("payload");

        if (payload == null) {
            return;
        }

        JsonObject session = payload.getAsJsonObject("session");

        if (session == null || !session.has("reconnect_url")) {
            return;
        }

        String reconnectUrl = session.get("reconnect_url").getAsString();

        if (reconnectUrl == null || reconnectUrl.isBlank()) {
            return;
        }

        System.out.println("[Stream Chat Bridge] Twitch requested EventSub migration.");

        connectTo(reconnectUrl, true);
    }

    private void setConnectionState(ConnectionState state) {
        connectionState = state;

        Consumer<ConnectionState> listener = stateListener;

        if (listener != null) {
            listener.accept(state);
        }
    }

    private void handleMessage(String json, long generation, boolean twitchReconnect) {
        JsonObject root = GSON.fromJson(json, JsonObject.class);

        JsonObject metadata = root.getAsJsonObject("metadata");

        if (metadata == null || !metadata.has("message_type")) {
            return;
        }

        String type = metadata.get("message_type").getAsString();

        switch (type) {
            case "session_welcome" -> handleWelcome(root, generation, twitchReconnect);

            case "notification" -> handleNotification(root);

            case "session_keepalive" -> {
            }

            case "session_reconnect" -> handleTwitchReconnect(root, generation);

            case "revocation" -> handleRevocation(root, generation);

            default -> {
            }
        }
    }

    private void handleWelcome(JsonObject root, long generation, boolean twitchReconnect) {
        if (generation != connectionGeneration) {
            return;
        }

        JsonObject payload = root.getAsJsonObject("payload");

        if (payload == null) {
            return;
        }

        JsonObject session = payload.getAsJsonObject("session");

        if (session == null || !session.has("id")) {
            return;
        }

        String sessionId = session.get("id").getAsString();

        if (session.has("keepalive_timeout_seconds") && !session.get("keepalive_timeout_seconds").isJsonNull()) {
            keepaliveTimeoutMs = session.get("keepalive_timeout_seconds").getAsLong() * 1000L;
        }

        lastMessageAt = System.currentTimeMillis();

        startKeepaliveWatchdog(generation);

        if (twitchReconnect) {
            System.out.println("[Stream Chat Bridge] Twitch EventSub migration complete.");

            resetReconnectBackoff();

            setConnectionState(ConnectionState.CONNECTED);

            return;
        }

        Threads.start("streamchatbridge-twitch-subscribe", () -> createChatSubscription(sessionId, generation));
    }

    /**
     * Twitch sends keepalives every few seconds; if nothing arrives within the
     * session's keepalive timeout, the socket is half-open (sleep, Wi-Fi switch)
     * and must be replaced.
     */
    private void startKeepaliveWatchdog(long generation) {
        Threads.start("streamchatbridge-twitch-keepalive", () -> {
            while (true) {
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException e) {
                    return;
                }

                synchronized (TwitchEventSubClient.this) {
                    if (generation != connectionGeneration || shuttingDown || !shouldStayConnected) {
                        return;
                    }

                    if (connectionState != ConnectionState.CONNECTED) {
                        continue;
                    }

                    long silentFor = System.currentTimeMillis() - lastMessageAt;

                    if (silentFor > keepaliveTimeoutMs + 15_000L) {
                        System.out.println("[Stream Chat Bridge] EventSub silent for " + silentFor + "ms; reconnecting.");

                        lastMessageAt = System.currentTimeMillis();

                        reconnect();

                        return;
                    }
                }
            }
        });
    }

    private void createChatSubscription(String sessionId, long generation) {
        try {
            if (generation != connectionGeneration || shuttingDown || !shouldStayConnected) {
                return;
            }

            JsonObject condition = new JsonObject();

            condition.addProperty("broadcaster_user_id", channelId);

            condition.addProperty("user_id", auth.getUserId());

            JsonObject transport = new JsonObject();

            transport.addProperty("method", "websocket");

            transport.addProperty("session_id", sessionId);

            JsonObject body = new JsonObject();

            body.addProperty("type", "channel.chat.message");

            body.addProperty("version", "1");

            body.add("condition", condition);

            body.add("transport", transport);

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(SUBSCRIPTIONS_URL)).header("Authorization", "Bearer " + auth.getAccessToken()).header("Client-Id", TwitchAuth.CLIENT_ID).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // The token can expire while idle; refresh once and retry the subscription.
            if (response.statusCode() == 401 && auth.ensureValidToken()) {
                request = HttpRequest.newBuilder().uri(URI.create(SUBSCRIPTIONS_URL)).header("Authorization", "Bearer " + auth.getAccessToken()).header("Client-Id", TwitchAuth.CLIENT_ID).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build();

                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            }

            if (generation != connectionGeneration) {
                return;
            }

            if (response.statusCode() != 202) {
                System.err.println("[Stream Chat Bridge] Failed to subscribe to Twitch chat. HTTP " + response.statusCode() + ": " + response.body());

                WebSocket socket = webSocket;

                webSocket = null;

                setConnectionState(ConnectionState.DISCONNECTED);

                if (socket != null) {
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "Subscription failed");
                }

                scheduleReconnect();

                return;
            }

            System.out.println("[Stream Chat Bridge] Twitch chat subscription active.");

            resetReconnectBackoff();

            setConnectionState(ConnectionState.CONNECTED);

        } catch (Exception e) {
            if (generation != connectionGeneration) {
                return;
            }

            System.err.println("[Stream Chat Bridge] Failed to create Twitch chat subscription: " + e.getMessage());

            WebSocket socket = webSocket;

            webSocket = null;

            setConnectionState(ConnectionState.DISCONNECTED);

            if (socket != null) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "Subscription failed");
            }

            scheduleReconnect();
        }
    }

    private void handleRevocation(JsonObject root, long generation) {
        if (generation != connectionGeneration) {
            return;
        }

        System.err.println("[Stream Chat Bridge] Twitch EventSub subscription revoked.");

        shouldStayConnected = false;
        reconnectScheduled = false;

        resetReconnectBackoff();

        WebSocket socket = webSocket;

        webSocket = null;

        setConnectionState(ConnectionState.DISCONNECTED);

        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "Subscription revoked");
        }
    }

    private void handleNotification(JsonObject root) {
        JsonObject payload = root.getAsJsonObject("payload");

        if (payload == null) {
            return;
        }

        JsonObject subscription = payload.getAsJsonObject("subscription");

        if (subscription == null || !subscription.has("type") || !"channel.chat.message".equals(subscription.get("type").getAsString())) {
            return;
        }

        JsonObject event = payload.getAsJsonObject("event");

        if (event == null) {
            return;
        }

        if (!event.has("chatter_user_name") || !event.has("message")) {
            return;
        }

        JsonObject messageObject = event.getAsJsonObject("message");

        if (messageObject == null || !messageObject.has("text")) {
            return;
        }

        String username = event.get("chatter_user_name").getAsString();

        String message = messageObject.get("text").getAsString();

        if (messageHandler != null) {
            messageHandler.accept(username, message);
        }
    }

    private final class EventSubSocketListener implements WebSocket.Listener {

        private final long generation;

        private final boolean twitchReconnect;

        private final StringBuilder buffer = new StringBuilder();

        private EventSubSocketListener(long generation, boolean twitchReconnect) {
            this.generation = generation;
            this.twitchReconnect = twitchReconnect;
        }

        @Override
        public void onOpen(WebSocket socket) {
            synchronized (TwitchEventSubClient.this) {
                if (generation != connectionGeneration || shuttingDown || !shouldStayConnected) {

                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "Stale connection");

                    return;
                }

                webSocket = socket;
            }

            System.out.println("[Stream Chat Bridge] EventSub WebSocket opened.");

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

                lastMessageAt = System.currentTimeMillis();

                try {
                    handleMessage(message, generation, twitchReconnect);
                } catch (Exception e) {
                    System.err.println("[Stream Chat Bridge] Failed to handle EventSub message: " + e.getMessage());
                }
            }

            socket.request(1);

            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            synchronized (TwitchEventSubClient.this) {
                if (generation != connectionGeneration) {
                    return null;
                }

                if (webSocket == socket) {
                    webSocket = null;
                }

                System.out.println("[Stream Chat Bridge] EventSub disconnected: " + statusCode + " " + reason);

                setConnectionState(ConnectionState.DISCONNECTED);

                scheduleReconnect();
            }

            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            synchronized (TwitchEventSubClient.this) {
                if (generation != connectionGeneration) {
                    return;
                }

                if (webSocket == socket) {
                    webSocket = null;
                }

                System.err.println("[Stream Chat Bridge] EventSub error: " + error.getMessage());

                setConnectionState(ConnectionState.DISCONNECTED);

                scheduleReconnect();
            }
        }
    }
}