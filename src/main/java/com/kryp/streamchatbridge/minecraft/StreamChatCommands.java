package com.kryp.streamchatbridge.minecraft;

import com.kryp.streamchatbridge.StreamChatBridgeClient;
import com.kryp.streamchatbridge.config.ConfigManager;
import com.kryp.streamchatbridge.config.ModConfig;
import com.kryp.streamchatbridge.kick.KickAuth;
import com.kryp.streamchatbridge.kick.KickChatClient;
import com.kryp.streamchatbridge.kick.KickClient;
import com.kryp.streamchatbridge.minecraft.ui.ScbScreens;
import com.kryp.streamchatbridge.minecraft.ui.StreamChatConfigScreen;
import com.kryp.streamchatbridge.twitch.TwitchAuth;
import com.kryp.streamchatbridge.twitch.TwitchClient;
import com.kryp.streamchatbridge.twitch.TwitchEventSubClient;
import com.kryp.streamchatbridge.util.Threads;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.MutableComponent;

//? if fabric {
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
//?}

public final class StreamChatCommands {

    private StreamChatCommands() {
    }

    public static void register() {
        //? if fabric {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(build()));
        //?}
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(build());
    }

    private static <S> LiteralArgumentBuilder<S> build() {
        return LiteralArgumentBuilder.<S>literal("scb")

                .executes(context -> {
                    openDashboard();
                    return 1;
                })

                .then(LiteralArgumentBuilder.<S>literal("config").executes(context -> {
                    openDashboard();
                    return 1;
                }))

                .then(LiteralArgumentBuilder.<S>literal("status").executes(context -> {
                    showStatus();
                    return 1;
                }))

                .then(LiteralArgumentBuilder.<S>literal("watch")

                        .then(LiteralArgumentBuilder.<S>literal("twitch")

                                .executes(context -> {
                                    showTwitchWatchStatus();
                                    return 1;
                                })

                                .then(RequiredArgumentBuilder.<S, String>argument("channel", StringArgumentType.word()).suggests((context, builder) -> {
                                    builder.suggest("self");
                                    return builder.buildFuture();
                                }).executes(context -> {
                                    watchTwitch(StringArgumentType.getString(context, "channel"));

                                    return 1;
                                })))

                        .then(LiteralArgumentBuilder.<S>literal("kick")

                                .executes(context -> {
                                    showKickWatchStatus();
                                    return 1;
                                })

                                .then(RequiredArgumentBuilder.<S, String>argument("channel", StringArgumentType.word()).suggests((context, builder) -> {
                                    builder.suggest("self");
                                    return builder.buildFuture();
                                }).executes(context -> {
                                    watchKick(StringArgumentType.getString(context, "channel"));

                                    return 1;
                                }))));
    }

    private static void openDashboard() {
        Minecraft minecraft = Minecraft.getInstance();

        minecraft.execute(() -> ScbScreens.open(new StreamChatConfigScreen(null)));
    }

    /*
     * Watch
     */

    private static void watchTwitch(String requestedChannel) {
        TwitchAuth auth = StreamChatBridgeClient.getTwitchAuth();

        if (!auth.isAuthenticated()) {
            showPlatformError(MinecraftChatBridge.twitch(), "Not logged in");

            return;
        }

        boolean self = requestedChannel.equalsIgnoreCase("self");

        String channel = self ? auth.getUsername() : normalizeChannel(requestedChannel);

        if (channel == null || channel.isBlank()) {
            showPlatformError(MinecraftChatBridge.twitch(), "Invalid channel");

            return;
        }

        showPlatformInfo(MinecraftChatBridge.twitch(), "Switching to " + channel + "...");

        Threads.start("streamchatbridge-twitch-watch", () -> {
            TwitchClient twitchClient = StreamChatBridgeClient.getTwitchClient();

            /*
             * setChannel() resolves the channel and also updates
             * TwitchClient's active broadcaster.
             *
             * Unlike the old temporary watch design, this is now
             * intentional because the selected channel is permanent.
             */
            if (!twitchClient.setChannel(self ? "" : channel)) {
                showPlatformError(MinecraftChatBridge.twitch(), "Channel not found");

                return;
            }

            TwitchEventSubClient eventSub = StreamChatBridgeClient.getTwitchEventSub();

            eventSub.disconnect();

            eventSub.setChannelId(twitchClient.getChannelId());

            eventSub.connect();

            ModConfig config = ConfigManager.get();

            config.twitchChannel = self ? "" : channel;

            ConfigManager.save();

            showWatching(MinecraftChatBridge.twitch(), self ? auth.getUsername() : channel);
        });
    }

    private static void watchKick(String requestedChannel) {
        KickAuth auth = StreamChatBridgeClient.getKickAuth();

        if (!auth.hasClientCredentials()) {
            showPlatformError(MinecraftChatBridge.kick(), "Setup required — press F8 to configure");

            return;
        }

        if (!auth.isAuthenticated()) {
            showPlatformError(MinecraftChatBridge.kick(), "Not logged in");

            return;
        }

        boolean self = requestedChannel.equalsIgnoreCase("self");

        String channel = self ? auth.getUsername() : normalizeChannel(requestedChannel);

        if (channel == null || channel.isBlank()) {
            showPlatformError(MinecraftChatBridge.kick(), "Invalid channel");

            return;
        }

        showPlatformInfo(MinecraftChatBridge.kick(), "Switching to " + channel + "...");

        Threads.start("streamchatbridge-kick-watch", () -> {
            KickClient client = StreamChatBridgeClient.getKickClient();

            KickChatClient chat = StreamChatBridgeClient.getKickChat();

            boolean channelLoaded;

            if (self) {
                channelLoaded = client.loadOwnChannel();
            } else {
                channelLoaded = client.setChannel(channel);
            }

            if (!channelLoaded) {
                showPlatformError(MinecraftChatBridge.kick(), "Channel not found");

                return;
            }

            if (!chat.connect(channel)) {
                showPlatformError(MinecraftChatBridge.kick(), "Could not connect to channel");

                return;
            }

            ModConfig config = ConfigManager.get();

            config.kickChannel = self ? "" : channel;

            ConfigManager.save();

            showWatching(MinecraftChatBridge.kick(), channel);
        });
    }

    private static void showTwitchWatchStatus() {
        TwitchAuth auth = StreamChatBridgeClient.getTwitchAuth();

        if (!auth.isAuthenticated()) {
            showPlatformError(MinecraftChatBridge.twitch(), "Not logged in");

            return;
        }

        String configured = ConfigManager.get().twitchChannel;

        String channel = configured == null || configured.isBlank() ? auth.getUsername() : configured;

        showWatching(MinecraftChatBridge.twitch(), channel);
    }

    private static void showKickWatchStatus() {
        KickAuth auth = StreamChatBridgeClient.getKickAuth();

        if (!auth.hasClientCredentials()) {
            showPlatformError(MinecraftChatBridge.kick(), "Setup required — press F8 to configure");

            return;
        }

        if (!auth.isAuthenticated()) {
            showPlatformError(MinecraftChatBridge.kick(), "Not logged in");

            return;
        }

        String configured = ConfigManager.get().kickChannel;

        String channel = configured == null || configured.isBlank() ? auth.getUsername() : configured;

        showWatching(MinecraftChatBridge.kick(), channel);
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

    private static void showWatching(MutableComponent platform, String channel) {
        MinecraftChatBridge.showLocalMessage(MinecraftChatBridge.systemMessage().append(platform).append(MinecraftChatBridge.separator(": ")).append(MinecraftChatBridge.success("Watching ")).append(MinecraftChatBridge.value(channel)));
    }

    private static void showPlatformInfo(MutableComponent platform, String message) {
        MinecraftChatBridge.showLocalMessage(MinecraftChatBridge.systemMessage().append(platform).append(MinecraftChatBridge.separator(": ")).append(MinecraftChatBridge.label(message)));
    }

    private static void showPlatformError(MutableComponent platform, String message) {
        MinecraftChatBridge.showLocalMessage(MinecraftChatBridge.systemMessage().append(platform).append(MinecraftChatBridge.separator(": ")).append(MinecraftChatBridge.error(message)));
    }

    /*
     * Status
     */

    private static void showStatus() {
        MinecraftChatBridge.showLocalMessage(MinecraftChatBridge.systemMessage());

        showTwitchStatus();
        showKickStatus();
    }

    private static void showTwitchStatus() {
        TwitchAuth auth = StreamChatBridgeClient.getTwitchAuth();

        TwitchEventSubClient eventSub = StreamChatBridgeClient.getTwitchEventSub();

        MutableComponent account = MinecraftChatBridge.twitch().append(MinecraftChatBridge.separator(" — "));

        if (auth.isAuthenticated()) {
            account.append(MinecraftChatBridge.value(auth.getUsername()));
        } else {
            account.append(MinecraftChatBridge.error("Not authenticated"));
        }

        MinecraftChatBridge.showLocalMessage(account);

        String configuredChannel = ConfigManager.get().twitchChannel;

        String channel;

        if (configuredChannel != null && !configuredChannel.isBlank()) {

            channel = configuredChannel;

        } else if (auth.isAuthenticated()) {

            channel = auth.getUsername();

        } else {

            channel = "Own channel";
        }

        MinecraftChatBridge.showLocalMessage(MinecraftChatBridge.label("Channel: ").append(MinecraftChatBridge.value(channel)));

        MutableComponent status = MinecraftChatBridge.label("Status: ");

        if (!auth.isAuthenticated()) {
            status.append(MinecraftChatBridge.error("Not authenticated"));
        } else {
            switch (eventSub.getConnectionState()) {
                case CONNECTED -> status.append(MinecraftChatBridge.success("Connected"));

                case CONNECTING -> status.append(MinecraftChatBridge.warning("Connecting..."));

                case DISCONNECTED -> status.append(MinecraftChatBridge.error("Disconnected"));
            }
        }

        MinecraftChatBridge.showLocalMessage(status);

        MutableComponent outgoing = MinecraftChatBridge.label("Minecraft → ").append(MinecraftChatBridge.twitch()).append(MinecraftChatBridge.separator(": "));

        if (ConfigManager.get().twitchSendEnabled) {
            outgoing.append(MinecraftChatBridge.success("ON"));
        } else {
            outgoing.append(MinecraftChatBridge.error("OFF"));
        }

        MinecraftChatBridge.showLocalMessage(outgoing);
    }

    private static void showKickStatus() {
        KickAuth auth = StreamChatBridgeClient.getKickAuth();

        KickChatClient chat = StreamChatBridgeClient.getKickChat();

        MutableComponent account = MinecraftChatBridge.kick().append(MinecraftChatBridge.separator(" — "));

        if (!auth.hasClientCredentials()) {
            account.append(MinecraftChatBridge.error("Not configured"));
        } else if (!auth.isAuthenticated()) {
            account.append(MinecraftChatBridge.error("Not authenticated"));
        } else {
            account.append(MinecraftChatBridge.value(auth.getUsername()));
        }

        MinecraftChatBridge.showLocalMessage(account);

        String configuredChannel = ConfigManager.get().kickChannel;

        String channel;

        if (configuredChannel != null && !configuredChannel.isBlank()) {

            channel = configuredChannel;

        } else if (auth.isAuthenticated()) {

            channel = auth.getUsername();

        } else {

            channel = "Own channel";
        }

        MinecraftChatBridge.showLocalMessage(MinecraftChatBridge.label("Channel: ").append(MinecraftChatBridge.value(channel)));

        MutableComponent status = MinecraftChatBridge.label("Status: ");

        if (!auth.hasClientCredentials()) {
            status.append(MinecraftChatBridge.error("Not configured"));
        } else if (!auth.isAuthenticated()) {
            status.append(MinecraftChatBridge.error("Not authenticated"));
        } else {
            switch (chat.getConnectionState()) {
                case CONNECTED -> status.append(MinecraftChatBridge.success("Connected"));

                case CONNECTING -> status.append(MinecraftChatBridge.warning("Connecting..."));

                case DISCONNECTED -> status.append(MinecraftChatBridge.error("Disconnected"));
            }
        }

        MinecraftChatBridge.showLocalMessage(status);

        MutableComponent outgoing = MinecraftChatBridge.label("Minecraft → ").append(MinecraftChatBridge.kick()).append(MinecraftChatBridge.separator(": "));

        if (ConfigManager.get().kickSendEnabled) {
            outgoing.append(MinecraftChatBridge.success("ON"));
        } else {
            outgoing.append(MinecraftChatBridge.error("OFF"));
        }

        MinecraftChatBridge.showLocalMessage(outgoing);
    }
}
