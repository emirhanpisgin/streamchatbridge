package com.kryp.streamchatbridge;

import com.kryp.streamchatbridge.config.ConfigManager;
import com.kryp.streamchatbridge.kick.KickAuth;
import com.kryp.streamchatbridge.kick.KickChatClient;
import com.kryp.streamchatbridge.kick.KickClient;
import com.kryp.streamchatbridge.minecraft.MinecraftChatBridge;
import com.kryp.streamchatbridge.minecraft.StreamChatCommands;
import com.kryp.streamchatbridge.minecraft.StreamChatKeybinds;
import com.kryp.streamchatbridge.twitch.TwitchAuth;
import com.kryp.streamchatbridge.twitch.TwitchClient;
import com.kryp.streamchatbridge.twitch.TwitchEventSubClient;
import com.kryp.streamchatbridge.util.Threads;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.MutableComponent;

public class StreamChatBridgeClient {

    private static final TwitchAuth TWITCH_AUTH = new TwitchAuth();

    private static final TwitchClient TWITCH_CLIENT = new TwitchClient(TWITCH_AUTH);

    private static final TwitchEventSubClient TWITCH_EVENT_SUB = new TwitchEventSubClient(TWITCH_AUTH, MinecraftChatBridge::showTwitchMessage);

    private static final KickAuth KICK_AUTH = new KickAuth();

    private static final KickClient KICK_CLIENT = new KickClient(KICK_AUTH);

    private static final KickChatClient KICK_CHAT = new KickChatClient(MinecraftChatBridge::showKickMessage);

    private static boolean wasInWorld = false;

    public static void initialize() {
        ConfigManager.load();

        StreamChatCommands.register();
        StreamChatKeybinds.register();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            TWITCH_EVENT_SUB.shutdown();

            KICK_CHAT.disconnect();
        }, "streamchatbridge-shutdown"));

        Threads.start("streamchatbridge-twitch-restore", () -> {
            if (TWITCH_AUTH.restoreSession()) {
                onTwitchAuthenticated();

            } else {
                StreamChatBridge.LOGGER.info("[Stream Chat Bridge] No valid Twitch session found.");
            }
        });

        Threads.start("streamchatbridge-kick-restore", () -> {
            if (KICK_AUTH.restoreSession()) {
                onKickAuthenticated();

            } else {
                StreamChatBridge.LOGGER.info("[Stream Chat Bridge] No valid Kick session found.");
            }
        });

        StreamChatBridge.LOGGER.info("[Stream Chat Bridge] Loaded");
    }

    /*
     * Client tick
     */

    public static void onClientTick(Minecraft client) {
        StreamChatKeybinds.handleTick(client);

        boolean inWorld = client.player != null;

        if (inWorld && !wasInWorld) {
            wasInWorld = true;

            Threads.start("streamchatbridge-join-status", () -> {
                try {
                    Thread.sleep(1_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    return;
                }

                client.execute(StreamChatBridgeClient::showJoinStatus);
            });
        } else if (!inWorld) {
            wasInWorld = false;
        }
    }

    /*
     * Twitch startup
     */

    private static void onTwitchAuthenticated() {
        StreamChatBridge.LOGGER.info("[Stream Chat Bridge] Twitch authenticated as: " + TWITCH_AUTH.getUsername());

        String configuredChannel = ConfigManager.get().twitchChannel;

        if (!TWITCH_CLIENT.setChannel(configuredChannel)) {
            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Could not use configured Twitch channel: " + configuredChannel);

            return;
        }

        TWITCH_EVENT_SUB.setChannelId(TWITCH_CLIENT.getChannelId());

        TWITCH_EVENT_SUB.connect();
    }

    /*
     * Kick startup
     */

    private static void onKickAuthenticated() {
        StreamChatBridge.LOGGER.info("[Stream Chat Bridge] Kick authenticated as: " + KICK_AUTH.getUsername());

        String username = KICK_AUTH.getUsername();

        if (username == null || username.isBlank()) {
            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Cannot start Kick chat: username is missing.");

            return;
        }

        String configuredChannel = ConfigManager.get().kickChannel;

        String channel = configuredChannel == null || configuredChannel.isBlank() ? username : configuredChannel.trim();

        boolean channelLoaded;

        if (configuredChannel == null || configuredChannel.isBlank()) {

            channelLoaded = KICK_CLIENT.loadOwnChannel();

        } else {

            channelLoaded = KICK_CLIENT.setChannel(channel);
        }

        if (!channelLoaded) {
            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Could not load Kick channel: " + channel);

            return;
        }

        if (!KICK_CHAT.connect(channel)) {
            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Could not connect to Kick chat: " + channel);
        }
    }

    /*
     * World join status
     */

    private static void showJoinStatus() {
        showTwitchJoinStatus();
        showKickJoinStatus();
    }

    private static void showTwitchJoinStatus() {
        if (!TWITCH_AUTH.isAuthenticated()) {
            MinecraftChatBridge.showLocalMessage(MinecraftChatBridge.systemMessage().append(MinecraftChatBridge.twitch()).append(MinecraftChatBridge.separator(": ")).append(MinecraftChatBridge.warning("Not logged in")));

            return;
        }

        TwitchEventSubClient.ConnectionState state = TWITCH_EVENT_SUB.getConnectionState();

        MutableComponent message = MinecraftChatBridge.systemMessage().append(MinecraftChatBridge.twitch()).append(MinecraftChatBridge.separator(": "));

        switch (state) {
            case CONNECTED -> {
                message.append(MinecraftChatBridge.success("Connected"));

                String channel = getTwitchDisplayChannel();

                if (channel != null) {
                    message.append(MinecraftChatBridge.separator(" → "));

                    message.append(MinecraftChatBridge.value(channel));
                }
            }

            case CONNECTING -> message.append(MinecraftChatBridge.warning("Connecting..."));

            case DISCONNECTED -> message.append(MinecraftChatBridge.error("Disconnected"));
        }

        MinecraftChatBridge.showLocalMessage(message);
    }

    private static void showKickJoinStatus() {
        if (!KICK_AUTH.hasClientCredentials()) {
            MinecraftChatBridge.showLocalMessage(MinecraftChatBridge.systemMessage().append(MinecraftChatBridge.kick()).append(MinecraftChatBridge.separator(": ")).append(MinecraftChatBridge.warning("Setup required")).append(MinecraftChatBridge.separator(" — ")).append(MinecraftChatBridge.label("Press F8 to configure")));

            return;
        }

        if (!KICK_AUTH.isAuthenticated()) {
            MinecraftChatBridge.showLocalMessage(MinecraftChatBridge.systemMessage().append(MinecraftChatBridge.kick()).append(MinecraftChatBridge.separator(": ")).append(MinecraftChatBridge.warning("Not logged in")));

            return;
        }

        KickChatClient.ConnectionState state = KICK_CHAT.getConnectionState();

        MutableComponent message = MinecraftChatBridge.systemMessage().append(MinecraftChatBridge.kick()).append(MinecraftChatBridge.separator(": "));

        switch (state) {
            case CONNECTED -> {
                message.append(MinecraftChatBridge.success("Connected"));

                String channel = getKickDisplayChannel();

                if (channel != null) {
                    message.append(MinecraftChatBridge.separator(" → "));

                    message.append(MinecraftChatBridge.value(channel));
                }
            }

            case CONNECTING -> message.append(MinecraftChatBridge.warning("Connecting..."));

            case DISCONNECTED -> message.append(MinecraftChatBridge.error("Disconnected"));
        }

        MinecraftChatBridge.showLocalMessage(message);
    }

    private static String getTwitchDisplayChannel() {
        String configuredChannel = ConfigManager.get().twitchChannel;

        if (configuredChannel != null && !configuredChannel.isBlank()) {

            return configuredChannel;
        }

        if (TWITCH_AUTH.isAuthenticated()) {
            return TWITCH_AUTH.getUsername();
        }

        return null;
    }

    private static String getKickDisplayChannel() {
        String configuredChannel = ConfigManager.get().kickChannel;

        if (configuredChannel != null && !configuredChannel.isBlank()) {

            return configuredChannel.trim();
        }

        if (KICK_AUTH.isAuthenticated()) {
            return KICK_AUTH.getUsername();
        }

        return null;
    }

    /*
     * Public platform access
     */

    public static TwitchAuth getTwitchAuth() {
        return TWITCH_AUTH;
    }

    public static TwitchClient getTwitchClient() {
        return TWITCH_CLIENT;
    }

    public static TwitchEventSubClient getTwitchEventSub() {
        return TWITCH_EVENT_SUB;
    }

    public static KickAuth getKickAuth() {
        return KICK_AUTH;
    }

    public static KickClient getKickClient() {
        return KICK_CLIENT;
    }

    public static KickChatClient getKickChat() {
        return KICK_CHAT;
    }
}
