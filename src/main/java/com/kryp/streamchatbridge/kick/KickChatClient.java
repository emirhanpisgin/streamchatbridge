package com.kryp.streamchatbridge.kick;

import com.kryp.streamchatbridge.StreamChatBridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kryp.streamchatbridge.chat.PlatformChatMessage;
import com.kryp.streamchatbridge.config.ConfigManager;
import com.kryp.streamchatbridge.config.ModConfig;
import com.kryp.streamchatbridge.util.Threads;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class KickChatClient {

    public enum ConnectionState {
        CONNECTING, CONNECTED, DISCONNECTED
    }

    /** Kick's negotiated realtime transport (guest flow), replacing the old Pusher reader. */
    private static final String REALTIME_BASE = "https://web.kick.com/api/v1/realtime";

    private static final String CLIENT_NAME = "streamchatbridge";

    private static final String CHAT_EVENT = "ChatMessageEvent";

    /** Refresh the guest session this long before the server-advertised TTL. */
    private static final long SESSION_REFRESH_MARGIN_MS = 300_000L;

    private static final long DEFAULT_SESSION_TTL_SECONDS = 2_700L;

    /** Kick sends emotes as {@code [emote:ID:NAME]}; only the name is shown. */
    private static final Pattern EMOTE_PATTERN = Pattern.compile("\\[emote:\\d+:([^\\]]+)\\]");

    private static final long[] RECONNECT_DELAYS_MS = {1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L};

    private static final Gson GSON = new Gson();

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final Consumer<PlatformChatMessage> messageHandler;

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

    /** Generation whose session refresh is already scheduled (guards duplicate timers). */
    private volatile long refreshScheduledFor = -1;

    public KickChatClient(Consumer<PlatformChatMessage> messageHandler) {
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

        String resolvedChatroomId = resolveChatroomId(username);

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

            Session session = prepareSession(chatroomId);

            if (generation != connectionGeneration || shuttingDown || !shouldStayConnected) {

                return false;
            }

            httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(session.url), new KickSocketListener(generation, session))
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
     * Realtime transport (Centrifugo over Kick's negotiated WebSocket)
     */

    private static final class Session {

        private final String url;

        private final String token;

        private final Map<String, String> channelTokens;

        private Session(String url, String token, Map<String, String> channelTokens) {
            this.url = url;
            this.token = token;
            this.channelTokens = channelTokens;
        }
    }

    private Session prepareSession(String resolvedChatroomId) throws IOException, InterruptedException {
        String clientId = UUID.randomUUID().toString();

        JsonArray providers = new JsonArray();

        JsonObject pusher = new JsonObject();

        pusher.addProperty("provider", "pusher");

        providers.add(pusher);

        JsonObject centrifugo = new JsonObject();

        centrifugo.addProperty("provider", "centrifugo");

        providers.add(centrifugo);

        JsonObject capabilities = new JsonObject();

        capabilities.add("accepted_providers", providers);

        JsonObject client = new JsonObject();

        client.addProperty("id", clientId);

        client.addProperty("type", "web");

        JsonObject connectionRequest = new JsonObject();

        connectionRequest.add("client", client);

        connectionRequest.add("capabilities", capabilities);

        JsonObject negotiated = postJson("/connection", connectionRequest);

        String mode = readString(negotiated, "mode");

        if (!"websocket".equals(mode)) {
            throw new IOException("Kick realtime negotiation did not return a WebSocket transport");
        }

        String socketUrl = null;

        if (negotiated.has("connections") && negotiated.get("connections").isJsonArray()) {
            for (JsonElement element : negotiated.getAsJsonArray("connections")) {
                if (!element.isJsonObject()) {

                    continue;
                }

                JsonObject entry = element.getAsJsonObject();

                if (!"centrifugo".equals(readString(entry, "provider"))) {

                    continue;
                }

                if (entry.has("credentials") && entry.get("credentials").isJsonObject()) {
                    socketUrl = readString(entry.getAsJsonObject("credentials"), "url");
                }

                break;
            }
        }

        if (socketUrl == null || socketUrl.isBlank()) {
            throw new IOException("Kick realtime negotiation returned no Centrifugo descriptor");
        }

        URI socketUri = URI.create(socketUrl);

        String host = socketUri.getHost();

        if (!"wss".equalsIgnoreCase(socketUri.getScheme()) || host == null || !host.endsWith(".kick.com")) {
            throw new IOException("Kick realtime negotiation returned an unexpected socket destination");
        }

        JsonObject authorizationRequest = new JsonObject();

        authorizationRequest.addProperty("client_id", clientId);

        JsonObject authorization = postJson("/auth/connection", authorizationRequest);

        String token = readString(authorization, "token");

        if (token == null || token.isBlank()) {
            throw new IOException("Kick realtime authorization returned no token");
        }

        Map<String, String> channelTokens = new HashMap<>();

        for (String channel : realtimeChannels(resolvedChatroomId)) {
            JsonObject channelRequest = new JsonObject();

            channelRequest.addProperty("channel", channel);

            JsonObject channelAuthorization = postJson("/auth/channel", channelRequest);

            String channelToken = readString(channelAuthorization, "token");

            if (channelToken != null && !channelToken.isBlank()) {
                channelTokens.put(channel, channelToken);
            }
        }

        return new Session(socketUrl, token, channelTokens);
    }

    private JsonObject postJson(String path, JsonObject body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(REALTIME_BASE + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Origin", "https://kick.com")
                .header("Referer", "https://kick.com/")
                .header("x-app-platform", "web")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Kick realtime " + path + " failed with HTTP " + response.statusCode());
        }

        JsonObject json = GSON.fromJson(response.body(), JsonObject.class);

        if (json == null) {
            throw new IOException("Kick realtime " + path + " returned an empty response");
        }

        if (json.has("data") && json.get("data").isJsonObject()) {
            return json.getAsJsonObject("data");
        }

        return json;
    }

    private void scheduleSessionRefresh(long generation, long ttlSeconds) {
        long ttl = ttlSeconds > 60 ? ttlSeconds : DEFAULT_SESSION_TTL_SECONDS;

        long delayMs = Math.max(30_000L, (ttl * 1_000L) - SESSION_REFRESH_MARGIN_MS);

        if (refreshScheduledFor == generation) {

            return;
        }

        refreshScheduledFor = generation;

        Threads.start("streamchatbridge-kick-session-refresh", () -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                return;
            }

            synchronized (KickChatClient.this) {
                if (shuttingDown || !shouldStayConnected || generation != connectionGeneration
                        || connectionState != ConnectionState.CONNECTED) {

                    return;
                }

                String targetUsername = username;

                if (targetUsername == null || targetUsername.isBlank()) {

                    return;
                }

                connectionGeneration++;

                long nextGeneration = connectionGeneration;

                cleanupConnection();

                StreamChatBridge.LOGGER.info("[Stream Chat Bridge] Refreshing Kick realtime session...");

                connectInternal(targetUsername, nextGeneration);
            }
        });
    }

    private static String[] realtimeChannels(String resolvedChatroomId) {
        return new String[]{"chatrooms." + resolvedChatroomId + ".v2", "chatroom_" + resolvedChatroomId};
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

    /**
     * Uses the manual override, then a cached id for the same channel, then the
     * unofficial lookup with retries. Successful lookups are cached in the config.
     */
    private String resolveChatroomId(String username) {
        ModConfig config = ConfigManager.get();

        String override = config.kickChatroomIdOverride;

        if (override != null && !override.isBlank()) {
            StreamChatBridge.LOGGER.info("[Stream Chat Bridge] Using configured Kick chatroom ID override.");

            return override.trim();
        }

        String normalized = normalizeUsername(username);

        if (normalized == null) {
            return null;
        }

        String cached = config.kickChatroomId;

        if (cached != null && !cached.isBlank() && normalized.equalsIgnoreCase(config.kickChatroomChannel)) {
            return cached;
        }

        String id = findChatroomIdWithRetry(normalized);

        if (id != null) {
            config.kickChatroomId = id;

            config.kickChatroomChannel = normalized;

            ConfigManager.save();
        }

        return id;
    }

    private String findChatroomIdWithRetry(String normalized) {
        long[] delays = {1_000L, 2_000L, 4_000L};

        for (int attempt = 0; attempt < delays.length; attempt++) {
            String id = findChatroomId(normalized);

            if (id != null) {
                return id;
            }

            if (attempt < delays.length - 1) {
                StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Retrying Kick chatroom lookup (" + (attempt + 2) + "/" + delays.length + ")...");

                try {
                    Thread.sleep(delays[attempt]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    return null;
                }
            }
        }

        StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick chatroom lookup failed after " + delays.length + " attempts. Set kickChatroomIdOverride in the config if it stays blocked.");

        return null;
    }

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

            message = EMOTE_PATTERN.matcher(message).replaceAll("$1");

            if (!data.has("sender") || !data.get("sender").isJsonObject()) {

                return;
            }

            JsonObject sender = data.getAsJsonObject("sender");

            String username = readString(sender, "username");

            if (username == null || username.isBlank()) {

                return;
            }

            int color = PlatformChatMessage.NO_COLOR;

            String badges = "";

            if (sender.has("identity") && sender.get("identity").isJsonObject()) {

                JsonObject identity = sender.getAsJsonObject("identity");

                color = PlatformChatMessage.parseColor(readString(identity, "color"));

                badges = readBadges(identity);
            }

            if (messageHandler != null) {
                messageHandler.accept(new PlatformChatMessage(username, message, color, badges));
            }

        } catch (Exception e) {
            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Could not process Kick chat message: " + e.getMessage());
        }
    }

    private static String readBadges(JsonObject identity) {
        if (!identity.has("badges") || !identity.get("badges").isJsonArray()) {
            return "";
        }

        StringBuilder badges = new StringBuilder();

        for (JsonElement element : identity.getAsJsonArray("badges")) {
            if (!element.isJsonObject()) {
                continue;
            }

            String tag = PlatformChatMessage.badgeTag(readString(element.getAsJsonObject(), "type"));

            if (!tag.isEmpty()) {
                if (badges.length() > 0) {
                    badges.append(' ');
                }

                badges.append(tag);
            }
        }

        return badges.toString();
    }

    private static String readString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {

            return null;
        }

        JsonElement value = object.get(key);

        return value.isJsonPrimitive() ? value.getAsString() : null;
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

    private final class KickSocketListener implements WebSocket.Listener {

        private final long generation;

        private final Session session;

        private final String[] channels;

        private final StringBuilder buffer = new StringBuilder();

        private int subscriptionAcks;

        private boolean subscriptionsRequested;

        private KickSocketListener(long generation, Session session) {
            this.generation = generation;
            this.session = session;
            this.channels = realtimeChannels(chatroomId);
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

            JsonObject connect = new JsonObject();

            connect.addProperty("token", session.token);

            connect.addProperty("name", CLIENT_NAME);

            JsonObject frame = new JsonObject();

            frame.addProperty("id", 1);

            frame.add("connect", connect);

            socket.sendText(GSON.toJson(frame), true);

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
                    handleFrames(message);
                } catch (Exception e) {
                    StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Failed to handle Kick chat frame: " + e.getMessage());
                }
            }

            socket.request(1);

            return null;
        }

        private void handleFrames(String raw) {
            for (String part : raw.split("\r?\n")) {
                if (part.isBlank()) {

                    continue;
                }

                JsonObject frame = GSON.fromJson(part, JsonObject.class);

                if (frame == null) {

                    continue;
                }

                if (frame.size() == 0) {
                    // Centrifugo application-level ping: answer with an empty object.
                    WebSocket socket = webSocket;

                    if (socket != null) {
                        socket.sendText("{}", true);
                    }

                    continue;
                }

                if (frame.has("error")) {
                    StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick realtime error: " + frame.get("error"));

                    fail();

                    return;
                }

                if (frame.has("disconnect")) {
                    StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Kick realtime disconnected by server: " + frame.get("disconnect"));

                    fail();

                    return;
                }

                JsonElement idElement = frame.get("id");

                boolean hasNumericId = idElement != null && idElement.isJsonPrimitive() && idElement.getAsJsonPrimitive().isNumber();

                if (hasNumericId) {
                    int id = idElement.getAsInt();

                    if (id == 1 && frame.has("connect") && frame.get("connect").isJsonObject()) {
                        JsonObject ack = frame.getAsJsonObject("connect");

                        long ttl = DEFAULT_SESSION_TTL_SECONDS;

                        JsonElement ttlElement = ack.get("ttl");

                        if (ttlElement != null && ttlElement.isJsonPrimitive() && ttlElement.getAsJsonPrimitive().isNumber()) {
                            ttl = ttlElement.getAsLong();
                        }

                        sendSubscriptions();

                        scheduleSessionRefresh(generation, ttl);

                        continue;
                    }

                    if (frame.has("subscribe")) {
                        subscriptionAcks++;

                        if (subscriptionsRequested && subscriptionAcks >= channels.length) {
                            synchronized (KickChatClient.this) {
                                if (generation != connectionGeneration) {

                                    return;
                                }

                                reconnectScheduled = false;

                                resetReconnectBackoff();

                                setConnectionState(ConnectionState.CONNECTED);
                            }
                        }

                        continue;
                    }
                }

                if (frame.has("push") && frame.get("push").isJsonObject()) {
                    JsonObject push = frame.getAsJsonObject("push");

                    if (!push.has("pub") || !push.get("pub").isJsonObject()) {

                        continue;
                    }

                    JsonObject publication = push.getAsJsonObject("pub");

                    handlePublication(publication);
                }
            }
        }

        private void handlePublication(JsonObject publication) {
            JsonElement payload = publication.get("data");

            if (payload == null || payload.isJsonNull()) {

                return;
            }

            JsonObject event;

            if (payload.isJsonPrimitive()) {
                event = GSON.fromJson(payload.getAsString(), JsonObject.class);
            } else if (payload.isJsonObject()) {
                event = payload.getAsJsonObject();
            } else {

                return;
            }

            if (event == null) {

                return;
            }

            String name = readString(event, "event");

            if (name == null) {
                name = readString(event, "type");
            }

            if (name == null) {

                return;
            }

            int separator = name.lastIndexOf('\\');

            if (separator >= 0) {
                name = name.substring(separator + 1);
            }

            if (!CHAT_EVENT.equals(name)) {

                return;
            }

            JsonElement body = event.get("data");

            if (body != null && body.isJsonPrimitive()) {
                handleChatEvent(body.getAsString());
            }
        }

        private void sendSubscriptions() {
            WebSocket socket = webSocket;

            if (socket == null || subscriptionsRequested) {

                return;
            }

            subscriptionsRequested = true;

            for (int index = 0; index < channels.length; index++) {
                String channel = channels[index];

                JsonObject subscribe = new JsonObject();

                subscribe.addProperty("channel", channel);

                String channelToken = session.channelTokens.get(channel);

                if (channelToken != null && !channelToken.isBlank()) {
                    subscribe.addProperty("token", channelToken);
                }

                JsonObject frame = new JsonObject();

                frame.addProperty("id", index + 2);

                frame.add("subscribe", subscribe);

                socket.sendText(GSON.toJson(frame), true);
            }
        }

        private void fail() {
            synchronized (KickChatClient.this) {
                if (generation != connectionGeneration) {

                    return;
                }

                WebSocket socket = webSocket;

                if (socket != null) {
                    try {
                        socket.sendClose(WebSocket.NORMAL_CLOSURE, "Reconnecting");
                    } catch (Exception ignored) {
                    }
                }

                webSocket = null;

                setConnectionState(ConnectionState.DISCONNECTED);

                scheduleReconnect();
            }
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
