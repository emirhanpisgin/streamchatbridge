package com.kryp.streamchatbridge.minecraft.ui;

import com.kryp.streamchatbridge.StreamChatBridgeClient;
import com.kryp.streamchatbridge.config.ConfigManager;
import com.kryp.streamchatbridge.kick.KickAuth;
import com.kryp.streamchatbridge.kick.KickChatClient;
import com.kryp.streamchatbridge.kick.KickClient;
import com.kryp.streamchatbridge.twitch.TwitchAuth;
import com.kryp.streamchatbridge.twitch.TwitchClient;
import com.kryp.streamchatbridge.twitch.TwitchEventSubClient;
import com.kryp.streamchatbridge.util.ScbText;
import com.kryp.streamchatbridge.util.Threads;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class StreamChatConfigScreen extends ScbScreen {

    private final Screen parent;

    private String statusMessage = "";

    private boolean twitchAuthenticationInProgress = false;
    private boolean kickAuthenticationInProgress = false;

    public StreamChatConfigScreen(Screen parent) {
        super(ScbText.literal("Stream Chat Bridge"));

        this.parent = parent;
    }

    @Override
    protected void init() {
        int totalWidth = 400;
        int columnWidth = 180;
        int gap = 40;

        int left = width / 2 - totalWidth / 2;

        int twitchLeft = left;
        int kickLeft = left + columnWidth + gap;

        createTwitchControls(twitchLeft, columnWidth);

        createKickControls(kickLeft, columnWidth);

        int bottomWidth = 300;
        int bottomLeft = width / 2 - bottomWidth / 2;

        addRenderableWidget(ScbButton.builder(ScbText.literal("Twitch Settings"), button -> {
            releaseFocus(button);
            setFocused(null);

            ScbScreens.open(new TwitchSettingsScreen(this));
        }).bounds(twitchLeft, 245, columnWidth, 20).build());

        addRenderableWidget(ScbButton.builder(ScbText.literal("Kick Settings"), button -> {
            releaseFocus(button);
            setFocused(null);

            ScbScreens.open(new KickSettingsScreen(this));
        }).bounds(kickLeft, 245, columnWidth, 20).build());

        addRenderableWidget(ScbButton.builder(ScbText.literal("Close"), button -> {
            releaseFocus(button);
            setFocused(null);

            ScbScreens.open(parent);
        }).bounds(bottomLeft, 290, bottomWidth, 20).build());

        StreamChatBridgeClient.getTwitchEventSub().setStateListener(this::twitchConnectionStateChanged);

        StreamChatBridgeClient.getKickChat().setStateListener(this::kickConnectionStateChanged);
    }

    /*
     * Twitch controls
     */

    private void createTwitchControls(int left, int width) {
        TwitchAuth auth = StreamChatBridgeClient.getTwitchAuth();

        TwitchEventSubClient eventSub = StreamChatBridgeClient.getTwitchEventSub();

        if (!auth.isAuthenticated()) {
            Button loginButton = ScbButton.builder(ScbText.literal(twitchAuthenticationInProgress ? "Waiting for Twitch..." : "Log in with Twitch"), button -> {
                releaseFocus(button);
                setFocused(null);

                twitchLogin();
            }).bounds(left, 155, width, 20).build();

            loginButton.active = !twitchAuthenticationInProgress;

            addRenderableWidget(loginButton);

            return;
        }

        Button connectionButton = ScbButton.builder(twitchConnectionButtonText(), button -> {
            releaseFocus(button);
            setFocused(null);

            TwitchEventSubClient.ConnectionState state = eventSub.getConnectionState();

            if (state == TwitchEventSubClient.ConnectionState.CONNECTED) {

                twitchReconnect();

            } else if (state == TwitchEventSubClient.ConnectionState.DISCONNECTED) {

                twitchConnect();
            }
        }).bounds(left, 155, width, 20).build();

        connectionButton.active = eventSub.getConnectionState() != TwitchEventSubClient.ConnectionState.CONNECTING;

        addRenderableWidget(connectionButton);

        Button disconnectButton = ScbButton.builder(ScbText.literal("Disconnect"), button -> {
            releaseFocus(button);
            setFocused(null);

            eventSub.disconnect();
        }).bounds(left, 185, width, 20).build();

        disconnectButton.active = eventSub.getConnectionState() != TwitchEventSubClient.ConnectionState.DISCONNECTED;

        addRenderableWidget(disconnectButton);

        addRenderableWidget(ScbButton.builder(ScbText.literal("Log Out"), button -> {
            releaseFocus(button);
            setFocused(null);

            twitchLogout();
        }).bounds(left, 215, width, 20).build());
    }

    /*
     * Kick controls
     */

    private void createKickControls(int left, int width) {
        KickAuth auth = StreamChatBridgeClient.getKickAuth();

        KickChatClient chat = StreamChatBridgeClient.getKickChat();

        if (!auth.hasClientCredentials()) {
            addRenderableWidget(ScbButton.builder(ScbText.literal("Set Up Kick"), button -> {
                releaseFocus(button);
                setFocused(null);

                ScbScreens.open(new KickSettingsScreen(this));
            }).bounds(left, 155, width, 20).build());

            return;
        }

        if (!auth.isAuthenticated()) {
            Button loginButton = ScbButton.builder(ScbText.literal(kickAuthenticationInProgress ? "Waiting for Kick..." : "Log in with Kick"), button -> {
                releaseFocus(button);
                setFocused(null);

                kickLogin();
            }).bounds(left, 155, width, 20).build();

            loginButton.active = !kickAuthenticationInProgress;

            addRenderableWidget(loginButton);

            return;
        }

        Button connectionButton = ScbButton.builder(kickConnectionButtonText(), button -> {
            releaseFocus(button);
            setFocused(null);

            KickChatClient.ConnectionState state = chat.getConnectionState();

            if (state == KickChatClient.ConnectionState.CONNECTED) {

                kickReconnect();

            } else if (state == KickChatClient.ConnectionState.DISCONNECTED) {

                kickConnect();
            }
        }).bounds(left, 155, width, 20).build();

        connectionButton.active = chat.getConnectionState() != KickChatClient.ConnectionState.CONNECTING;

        addRenderableWidget(connectionButton);

        Button disconnectButton = ScbButton.builder(ScbText.literal("Disconnect"), button -> {
            releaseFocus(button);
            setFocused(null);

            chat.disconnect();
        }).bounds(left, 185, width, 20).build();

        disconnectButton.active = chat.getConnectionState() != KickChatClient.ConnectionState.DISCONNECTED;

        addRenderableWidget(disconnectButton);

        addRenderableWidget(ScbButton.builder(ScbText.literal("Log Out"), button -> {
            releaseFocus(button);
            setFocused(null);

            kickLogout();
        }).bounds(left, 215, width, 20).build());
    }

    /*
     * Twitch actions
     */

    private Component twitchConnectionButtonText() {
        TwitchEventSubClient.ConnectionState state = StreamChatBridgeClient.getTwitchEventSub().getConnectionState();

        return ScbText.literal(switch (state) {
            case CONNECTED -> "Reconnect to Twitch";

            case CONNECTING -> "Connecting...";

            case DISCONNECTED -> "Connect to Twitch";
        });
    }

    private void twitchLogin() {
        if (twitchAuthenticationInProgress) {
            return;
        }

        TwitchAuth auth = StreamChatBridgeClient.getTwitchAuth();

        twitchAuthenticationInProgress = true;

        statusMessage = "Waiting for Twitch authorization...";

        rebuild();

        Threads.start("streamchatbridge-twitch-login", () -> {
            boolean authenticated = auth.authenticate();

            if (minecraft == null) {
                return;
            }

            minecraft.execute(() -> {
                twitchAuthenticationInProgress = false;

                if (!authenticated) {
                    statusMessage = "Twitch authentication failed";

                    rebuild();

                    return;
                }

                statusMessage = "Logged in to Twitch as " + auth.getUsername();

                prepareTwitchAccount();

                rebuild();
            });
        });
    }

    private void prepareTwitchAccount() {
        TwitchClient twitchClient = StreamChatBridgeClient.getTwitchClient();

        TwitchEventSubClient eventSub = StreamChatBridgeClient.getTwitchEventSub();

        String configuredChannel = ConfigManager.get().twitchChannel;

        Threads.start("streamchatbridge-twitch-channel", () -> {
            if (!twitchClient.setChannel(configuredChannel)) {
                if (minecraft != null) {
                    minecraft.execute(() -> {
                        statusMessage = "Could not select Twitch channel";

                        rebuild();
                    });
                }

                return;
            }

            eventSub.setChannelId(twitchClient.getChannelId());

            eventSub.connect();
        });
    }

    private void twitchLogout() {
        TwitchAuth auth = StreamChatBridgeClient.getTwitchAuth();

        TwitchEventSubClient eventSub = StreamChatBridgeClient.getTwitchEventSub();

        eventSub.disconnect();
        auth.logout();

        statusMessage = "Logged out of Twitch";

        rebuild();
    }

    private void twitchConnect() {
        TwitchAuth auth = StreamChatBridgeClient.getTwitchAuth();

        TwitchClient twitchClient = StreamChatBridgeClient.getTwitchClient();

        TwitchEventSubClient eventSub = StreamChatBridgeClient.getTwitchEventSub();

        if (!auth.isAuthenticated()) {
            statusMessage = "Twitch account is not authenticated";

            rebuild();

            return;
        }

        if (twitchClient.getChannelId() == null) {
            statusMessage = "No Twitch channel selected";

            rebuild();

            return;
        }

        eventSub.setChannelId(twitchClient.getChannelId());

        eventSub.connect();
    }

    private void twitchReconnect() {
        TwitchAuth auth = StreamChatBridgeClient.getTwitchAuth();

        TwitchClient twitchClient = StreamChatBridgeClient.getTwitchClient();

        TwitchEventSubClient eventSub = StreamChatBridgeClient.getTwitchEventSub();

        if (!auth.isAuthenticated()) {
            statusMessage = "Twitch account is not authenticated";

            rebuild();

            return;
        }

        if (twitchClient.getChannelId() == null) {
            statusMessage = "No Twitch channel selected";

            rebuild();

            return;
        }

        eventSub.setChannelId(twitchClient.getChannelId());

        eventSub.reconnect();
    }

    private void twitchConnectionStateChanged(TwitchEventSubClient.ConnectionState state) {
        if (minecraft == null) {
            return;
        }

        minecraft.execute(() -> {
            statusMessage = switch (state) {
                case CONNECTING -> "Connecting Twitch...";

                case CONNECTED -> "Twitch connected";

                case DISCONNECTED -> "Twitch disconnected";
            };

            rebuild();
        });
    }

    /*
     * Kick actions
     */

    private Component kickConnectionButtonText() {
        KickChatClient.ConnectionState state = StreamChatBridgeClient.getKickChat().getConnectionState();

        return ScbText.literal(switch (state) {
            case CONNECTED -> "Reconnect to Kick";

            case CONNECTING -> "Connecting...";

            case DISCONNECTED -> "Connect to Kick";
        });
    }

    private void kickLogin() {
        if (kickAuthenticationInProgress) {
            return;
        }

        KickAuth auth = StreamChatBridgeClient.getKickAuth();

        if (!auth.hasClientCredentials()) {
            statusMessage = "Configure Kick credentials first";

            rebuild();

            return;
        }

        kickAuthenticationInProgress = true;

        statusMessage = "Waiting for Kick authorization...";

        rebuild();

        Threads.start("streamchatbridge-kick-login", () -> {
            boolean authenticated = auth.authenticate();

            if (minecraft == null) {
                return;
            }

            minecraft.execute(() -> {
                kickAuthenticationInProgress = false;

                if (!authenticated) {
                    statusMessage = "Kick authentication failed";

                    rebuild();

                    return;
                }

                statusMessage = "Logged in to Kick as " + auth.getUsername();

                rebuild();

                kickConnect();
            });
        });
    }

    private void kickConnect() {
        KickAuth auth = StreamChatBridgeClient.getKickAuth();

        KickClient client = StreamChatBridgeClient.getKickClient();

        KickChatClient chat = StreamChatBridgeClient.getKickChat();

        if (!auth.isAuthenticated()) {
            statusMessage = "Kick account is not authenticated";

            rebuild();

            return;
        }

        if (chat.getConnectionState() == KickChatClient.ConnectionState.CONNECTING) {
            return;
        }

        String configuredChannel = ConfigManager.get().kickChannel;

        boolean useOwnChannel = configuredChannel == null || configuredChannel.isBlank();

        String channel = useOwnChannel ? auth.getUsername() : configuredChannel.trim();

        if (channel == null || channel.isBlank()) {
            statusMessage = "No Kick channel selected";

            rebuild();

            return;
        }

        Threads.start("streamchatbridge-kick-connect", () -> {
            boolean channelLoaded;

            if (useOwnChannel) {
                channelLoaded = client.loadOwnChannel();
            } else {
                channelLoaded = client.setChannel(channel);
            }

            if (!channelLoaded) {
                if (minecraft != null) {
                    minecraft.execute(() -> {
                        statusMessage = "Could not load Kick channel";

                        rebuild();
                    });
                }

                return;
            }

            boolean started = chat.connect(channel);

            if (!started && minecraft != null) {
                minecraft.execute(() -> {
                    statusMessage = "Could not connect to Kick chat";

                    rebuild();
                });
            }
        });
    }

    private void kickReconnect() {
        KickChatClient chat = StreamChatBridgeClient.getKickChat();

        chat.disconnect();

        kickConnect();
    }

    private void kickLogout() {
        KickAuth auth = StreamChatBridgeClient.getKickAuth();

        KickClient client = StreamChatBridgeClient.getKickClient();

        KickChatClient chat = StreamChatBridgeClient.getKickChat();

        chat.disconnect();
        client.clearChannel();
        auth.logout();

        statusMessage = "Logged out of Kick";

        rebuild();
    }

    private void kickConnectionStateChanged(KickChatClient.ConnectionState state) {
        if (minecraft == null) {
            return;
        }

        minecraft.execute(() -> {
            statusMessage = switch (state) {
                case CONNECTING -> "Connecting Kick...";

                case CONNECTED -> "Kick connected";

                case DISCONNECTED -> "Kick disconnected";
            };

            rebuild();
        });
    }

    /*
     * Twitch display
     */

    private String twitchAccountText() {
        TwitchAuth auth = StreamChatBridgeClient.getTwitchAuth();

        return auth.isAuthenticated() ? auth.getUsername() : "Not logged in";
    }

    private String twitchChannelText() {
        TwitchAuth auth = StreamChatBridgeClient.getTwitchAuth();

        if (!auth.isAuthenticated()) {
            return "—";
        }

        String channel = ConfigManager.get().twitchChannel;

        if (channel != null && !channel.isBlank()) {

            return channel;
        }

        return auth.getUsername();
    }

    private String twitchConnectionText() {
        if (!StreamChatBridgeClient.getTwitchAuth().isAuthenticated()) {

            return "Not logged in";
        }

        return switch (StreamChatBridgeClient.getTwitchEventSub().getConnectionState()) {
            case CONNECTED -> "Connected";

            case CONNECTING -> "Connecting...";

            case DISCONNECTED -> "Disconnected";
        };
    }

    /*
     * Kick display
     */

    private String kickAppText() {
        return StreamChatBridgeClient.getKickAuth().hasClientCredentials() ? "Configured" : "Setup required";
    }

    private String kickAccountText() {
        KickAuth auth = StreamChatBridgeClient.getKickAuth();

        return auth.isAuthenticated() ? auth.getUsername() : "Not logged in";
    }

    private String kickChannelText() {
        KickAuth auth = StreamChatBridgeClient.getKickAuth();

        if (!auth.isAuthenticated()) {
            return "—";
        }

        String channel = ConfigManager.get().kickChannel;

        if (channel != null && !channel.isBlank()) {
            return channel;
        }

        return auth.getUsername();
    }

    private String kickConnectionText() {
        KickAuth auth = StreamChatBridgeClient.getKickAuth();

        if (!auth.hasClientCredentials()) {
            return "Not configured";
        }

        if (!auth.isAuthenticated()) {
            return "Not logged in";
        }

        return switch (StreamChatBridgeClient.getKickChat().getConnectionState()) {
            case CONNECTED -> "Connected";

            case CONNECTING -> "Connecting...";

            case DISCONNECTED -> "Disconnected";
        };
    }

    @Override
    public void onClose() {
        StreamChatBridgeClient.getTwitchEventSub().setStateListener(null);

        StreamChatBridgeClient.getKickChat().setStateListener(null);

        ScbScreens.open(parent);
    }

    @Override
    public void removed() {
        StreamChatBridgeClient.getTwitchEventSub().setStateListener(null);

        StreamChatBridgeClient.getKickChat().setStateListener(null);

        super.removed();
    }

    @Override
    protected void renderContent(ScbGui graphics, int mouseX, int mouseY, float delta) {
        int totalWidth = 400;
        int columnWidth = 180;
        int gap = 40;

        int left = width / 2 - totalWidth / 2;

        int twitchLeft = left;
        int kickLeft = left + columnWidth + gap;

        graphics.text(font, title, width / 2 - font.width(title) / 2, 25, 0xFFFFFFFF, true);

        /*
         * Twitch
         */

        graphics.text(font, "TWITCH", twitchLeft, 65, 0xFFAA55AA, false);

        graphics.text(font, "Account", twitchLeft, 85, 0xFF888888, false);

        graphics.text(font, twitchAccountText(), twitchLeft + 65, 85, 0xFFFFFFFF, false);

        graphics.text(font, "Channel", twitchLeft, 102, 0xFF888888, false);

        graphics.text(font, twitchChannelText(), twitchLeft + 65, 102, 0xFFFFFFFF, false);

        graphics.text(font, "Status", twitchLeft, 119, 0xFF888888, false);

        graphics.text(font, twitchConnectionText(), twitchLeft + 65, 119, 0xFFFFFFFF, false);

        /*
         * Kick
         */

        graphics.text(font, "KICK", kickLeft, 65, 0xFF53FC18, false);

        graphics.text(font, "App", kickLeft, 85, 0xFF888888, false);

        graphics.text(font, kickAppText(), kickLeft + 65, 85, 0xFFFFFFFF, false);

        graphics.text(font, "Account", kickLeft, 102, 0xFF888888, false);

        graphics.text(font, kickAccountText(), kickLeft + 65, 102, 0xFFFFFFFF, false);

        graphics.text(font, "Channel", kickLeft, 119, 0xFF888888, false);

        graphics.text(font, kickChannelText(), kickLeft + 65, 119, 0xFFFFFFFF, false);

        graphics.text(font, "Status", kickLeft, 136, 0xFF888888, false);

        graphics.text(font, kickConnectionText(), kickLeft + 65, 136, 0xFFFFFFFF, false);

        if (!statusMessage.isBlank()) {
            graphics.text(font, statusMessage, width / 2 - font.width(statusMessage) / 2, 325, 0xFFAAAAAA, false);
        }
    }
}
