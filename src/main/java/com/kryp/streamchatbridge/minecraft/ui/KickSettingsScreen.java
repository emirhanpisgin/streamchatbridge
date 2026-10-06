package com.kryp.streamchatbridge.minecraft.ui;

import com.kryp.streamchatbridge.StreamChatBridgeClient;
import com.kryp.streamchatbridge.config.ConfigManager;
import com.kryp.streamchatbridge.config.ModConfig;
import com.kryp.streamchatbridge.kick.KickAuth;
import com.kryp.streamchatbridge.minecraft.MinecraftChatBridge;
import com.kryp.streamchatbridge.util.BrowserUtils;
import com.kryp.streamchatbridge.util.ScbText;
import com.kryp.streamchatbridge.util.Threads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class KickSettingsScreen extends ScbScreen {

    private static final String DEVELOPER_URL = "https://dev.kick.com/";

    private static final ColorOption[] COLORS = {new ColorOption("Reset", "<reset>", null), new ColorOption("White", "<white>", ChatFormatting.WHITE), new ColorOption("Gray", "<gray>", ChatFormatting.GRAY), new ColorOption("Dark Gray", "<dark_gray>", ChatFormatting.DARK_GRAY), new ColorOption("Black", "<black>", ChatFormatting.BLACK), new ColorOption("Red", "<red>", ChatFormatting.RED), new ColorOption("Dark Red", "<dark_red>", ChatFormatting.DARK_RED), new ColorOption("Gold", "<gold>", ChatFormatting.GOLD), new ColorOption("Yellow", "<yellow>", ChatFormatting.YELLOW), new ColorOption("Green", "<green>", ChatFormatting.GREEN), new ColorOption("Dark Green", "<dark_green>", ChatFormatting.DARK_GREEN), new ColorOption("Aqua", "<aqua>", ChatFormatting.AQUA), new ColorOption("Dark Aqua", "<dark_aqua>", ChatFormatting.DARK_AQUA), new ColorOption("Blue", "<blue>", ChatFormatting.BLUE), new ColorOption("Dark Blue", "<dark_blue>", ChatFormatting.DARK_BLUE), new ColorOption("Light Purple", "<light_purple>", ChatFormatting.LIGHT_PURPLE), new ColorOption("Dark Purple", "<dark_purple>", ChatFormatting.DARK_PURPLE)};

    private final Screen parent;

    private EditBox clientIdField;
    private EditBox clientSecretField;

    private EditBox channelField;
    private EditBox prefixField;
    private EditBox platformField;
    private EditBox formatField;

    private Button saveButton;

    private int selectedColorIndex = 9;

    private String statusMessage = "";

    private boolean setupMode;

    private boolean editingCredentials = false;

    private boolean channelChangeInProgress;

    public KickSettingsScreen(Screen parent) {
        super(ScbText.literal("Kick Settings"));

        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();

        KickAuth auth = StreamChatBridgeClient.getKickAuth();

        if (!auth.hasClientCredentials()) {
            setupMode = true;
        }

        if (setupMode) {
            initSetup();
        } else {
            initSettings();
        }
    }

    /*
     * Setup
     */

    private void initSetup() {
        KickAuth auth = StreamChatBridgeClient.getKickAuth();

        int contentWidth = 400;
        int left = width / 2 - contentWidth / 2;

        addRenderableWidget(ScbButton.builder(
                ScbText.literal("Open Kick Developer Portal"),
                button -> {
                    BrowserUtils.open(DEVELOPER_URL);
                    release(button);
                }
        ).bounds(left, 82, contentWidth, 20).build());

        addRenderableWidget(ScbButton.builder(
                ScbText.literal("Copy Redirect URL"),
                button -> {
                    Minecraft.getInstance()
                            .keyboardHandler
                            .setClipboard(KickAuth.getRedirectUri());

                    statusMessage = "Redirect URL copied";

                    release(button);
                }
        ).bounds(left, 137, contentWidth, 20).build());

        clientIdField = new EditBox(
                font,
                left,
                279,
                contentWidth,
                20,
                ScbText.literal("Client ID")
        );

        clientIdField.setValue(
                auth.getClientId() == null
                        ? ""
                        : auth.getClientId()
        );

        clientIdField.setMaxLength(256);

        addRenderableWidget(clientIdField);

        clientSecretField = new EditBox(
                font,
                left,
                324,
                contentWidth,
                20,
                ScbText.literal("Client Secret")
        );

        clientSecretField.setValue("");
        clientSecretField.setMaxLength(512);

        //? if >=1.21.9 {
        clientSecretField.addFormatter(
                (text, position) ->
                        ScbText.literal(
                                "•".repeat(text.length())
                        ).getVisualOrderText()
        );
        //?} else {
        /*clientSecretField.setFormatter(
                (text, position) ->
                        ScbText.literal(
                                "•".repeat(text.length())
                        ).getVisualOrderText()
        );
        *///?}

        addRenderableWidget(clientSecretField);

        addRenderableWidget(ScbButton.builder(
                ScbText.literal(
                        editingCredentials
                                ? "Save Credentials"
                                : "Save & Continue"
                ),
                button -> {
                    saveSetup();
                    release(button);
                }
        ).bounds(left, 360, 195, 20).build());

        addRenderableWidget(ScbButton.builder(
                ScbText.literal("Back"),
                button -> {
                    release(button);

                    if (editingCredentials) {
                        editingCredentials = false;
                        setupMode = false;

                        rebuild();
                    } else {
                        ScbScreens.open(parent);
                    }
                }
        ).bounds(left + 205, 360, 195, 20).build());

        if (editingCredentials) {
            addRenderableWidget(ScbButton.builder(
                    ScbText.literal("Reset Kick App"),
                    button -> {
                        resetKickApp();
                        release(button);
                    }
            ).bounds(left, 395, contentWidth, 20).build());
        }
    }

    /*
     * Settings
     */

    private void initSettings() {
        ModConfig config = ConfigManager.get();

        int contentWidth = 400;
        int left = width / 2 - contentWidth / 2;

        channelField = new EditBox(font, left, 70, contentWidth, 20, ScbText.literal("Channel"));

        channelField.setValue(config.kickChannel == null ? "" : config.kickChannel);

        channelField.setMaxLength(100);

        addRenderableWidget(channelField);

        prefixField = new EditBox(font, left, 110, contentWidth, 20, ScbText.literal("Outgoing Prefix"));

        prefixField.setValue(config.kickOutgoingPrefix == null ? "!k " : config.kickOutgoingPrefix);

        prefixField.setMaxLength(30);

        addRenderableWidget(prefixField);

        platformField = new EditBox(font, left, 150, contentWidth, 20, ScbText.literal("Platform Label"));

        platformField.setValue(config.kickIncomingPlatformLabel == null ? "Kick" : config.kickIncomingPlatformLabel);

        platformField.setMaxLength(30);

        addRenderableWidget(platformField);

        formatField = new EditBox(font, left, 190, contentWidth, 20, ScbText.literal("Incoming Format"));

        String format = config.kickIncomingMessageFormat;

        if (format == null || format.isBlank()) {
            format = MinecraftChatBridge.KICK_DEFAULT_FORMAT;
        }

        formatField.setMaxLength(512);
        formatField.setValue(format);

        addRenderableWidget(formatField);

        addRenderableWidget(ScbButton.builder(selectedColorText(), button -> {
            selectedColorIndex++;

            if (selectedColorIndex >= COLORS.length) {
                selectedColorIndex = 0;
            }

            button.setMessage(selectedColorText());

            release(button);
        }).bounds(left, 225, 260, 20).build());

        addRenderableWidget(ScbButton.builder(ScbText.literal("Insert"), button -> {
            insertSelectedColor();
            release(button);
        }).bounds(left + 270, 225, 130, 20).build());

        addRenderableWidget(ScbButton.builder(sendToggleText(), button -> {
            config.kickSendEnabled = !config.kickSendEnabled;

            button.setMessage(sendToggleText());

            ConfigManager.save();

            release(button);
        }).bounds(left, 280, contentWidth, 20).build());

        addRenderableWidget(ScbButton.builder(ScbText.literal("App Credentials"), button -> {
            openCredentialEditor();
            release(button);
        }).bounds(left, 315, contentWidth, 20).build());

        saveButton = addRenderableWidget(ScbButton.builder(ScbText.literal("Save"), button -> {
            saveSettings();
            release(button);
        }).bounds(left, 350, 195, 20).build());

        saveButton.active = !channelChangeInProgress;

        addRenderableWidget(ScbButton.builder(ScbText.literal("Back"), button -> {
            saveSettings();
            release(button);

            ScbScreens.open(parent);
        }).bounds(left + 205, 350, 195, 20).build());
    }

    private void resetKickApp() {
        KickAuth auth = StreamChatBridgeClient.getKickAuth();

        StreamChatBridgeClient.getKickChat().disconnect();

        StreamChatBridgeClient.getKickClient().clearChannel();

        auth.resetClientCredentials();

        clientIdField = null;
        clientSecretField = null;

        editingCredentials = false;
        setupMode = true;

        statusMessage = "Kick app credentials removed";

        rebuild();
    }

    private void openCredentialEditor() {
        editingCredentials = true;
        setupMode = true;

        rebuild();
    }

    /*
     * Save setup
     */

    private void saveSetup() {
        KickAuth auth = StreamChatBridgeClient.getKickAuth();

        String clientId = clientIdField.getValue().trim();

        String clientSecret = clientSecretField.getValue().trim();

        if (clientId.isBlank()) {
            statusMessage = "Client ID is required";
            return;
        }

        boolean clientIdChanged = auth.getClientId() == null || !clientId.equals(auth.getClientId());

        if (clientSecret.isBlank()) {
            if (!editingCredentials || clientIdChanged) {
                statusMessage = "Client Secret is required";
                return;
            }

            /*
             * Existing credentials weren't changed.
             * There is nothing to update.
             */
            statusMessage = "Credentials unchanged";

            editingCredentials = false;
            setupMode = false;

            rebuild();

            return;
        }

        StreamChatBridgeClient.getKickChat().disconnect();

        StreamChatBridgeClient.getKickClient().clearChannel();

        auth.setClientCredentials(clientId, clientSecret);

        statusMessage = "Kick app configured";

        editingCredentials = false;
        setupMode = false;

        rebuild();
    }

    /*
     * Save settings
     */

    private void saveSettings() {
        if (channelChangeInProgress) {
            return;
        }

        if (channelField == null || prefixField == null || platformField == null || formatField == null) {

            return;
        }

        ModConfig config = ConfigManager.get();

        String oldChannel = config.kickChannel == null ? "" : config.kickChannel.trim();

        String newChannel = normalizeChannel(channelField.getValue());

        config.kickOutgoingPrefix = prefixField.getValue();

        config.kickIncomingPlatformLabel = platformField.getValue().isBlank() ? "Kick" : platformField.getValue();

        config.kickIncomingMessageFormat = formatField.getValue().isBlank() ? MinecraftChatBridge.KICK_DEFAULT_FORMAT : formatField.getValue();

        /*
         * If the channel didn't change, save everything normally.
         */
        if (oldChannel.equalsIgnoreCase(newChannel)) {
            ConfigManager.save();

            statusMessage = "Saved";

            return;
        }

        /*
         * If we're not authenticated, we can't validate the channel now.
         * Save it and let startup resolve it after authentication.
         */
        KickAuth auth = StreamChatBridgeClient.getKickAuth();

        if (!auth.isAuthenticated()) {
            config.kickChannel = newChannel;

            ConfigManager.save();

            statusMessage = "Saved";

            return;
        }

        /*
         * Channel changed while authenticated.
         * applyChannel() will persist it only after a successful switch.
         */
        applyChannel(newChannel);
    }

    private void applyChannel(String configuredChannel) {
        KickAuth auth = StreamChatBridgeClient.getKickAuth();

        if (!auth.isAuthenticated()) {
            return;
        }

        boolean useOwnChannel = configuredChannel == null || configuredChannel.isBlank();

        String channel = useOwnChannel ? auth.getUsername() : configuredChannel.trim();

        if (channel == null || channel.isBlank()) {
            statusMessage = "Kick username unavailable";

            return;
        }

        channelChangeInProgress = true;

        if (saveButton != null) {
            saveButton.active = false;
        }

        statusMessage = "Changing channel...";

        Threads.start("streamchatbridge-kick-channel", () -> {
            var client = StreamChatBridgeClient.getKickClient();

            var chat = StreamChatBridgeClient.getKickChat();

            boolean channelLoaded;

            if (useOwnChannel) {
                channelLoaded = client.loadOwnChannel();
            } else {
                channelLoaded = client.setChannel(channel);
            }

            if (!channelLoaded) {
                setStatus("Channel not found");
                return;
            }

            if (!chat.connect(channel)) {
                setStatus("Could not connect to channel");
                return;
            }

            ModConfig config = ConfigManager.get();

            config.kickChannel = useOwnChannel ? "" : configuredChannel.trim();

            ConfigManager.save();

            setStatus("Saved");
        });
    }

    private String normalizeChannel(String channel) {
        if (channel == null) {
            return "";
        }

        String normalized = channel.trim();

        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }

        return normalized.trim();
    }

    private void setStatus(String message) {
        if (minecraft == null) {
            return;
        }

        minecraft.execute(() -> {
            statusMessage = message;
            channelChangeInProgress = false;

            if (saveButton != null) {
                saveButton.active = true;
            }
        });
    }

    /*
     * UI helpers
     */

    private Component sendToggleText() {
        return ScbText.literal("Minecraft → Kick: " + (ConfigManager.get().kickSendEnabled ? "ON" : "OFF"));
    }

    private Component selectedColorText() {
        ColorOption option = COLORS[selectedColorIndex];

        Component value = ScbText.literal(option.name());

        if (option.formatting() != null) {
            value = value.copy().withStyle(option.formatting());
        }

        return ScbText.literal("Color: ").append(value);
    }

    private void insertSelectedColor() {
        ColorOption option = COLORS[selectedColorIndex];

        String value = formatField.getValue();

        int cursor = Math.max(0, Math.min(formatField.getCursorPosition(), value.length()));

        String updated = value.substring(0, cursor) + option.tag() + value.substring(cursor);

        formatField.setValue(updated);

        formatField.setCursorPosition(cursor + option.tag().length());

        takeFocus(formatField);
        setFocused(formatField);
    }

    private Component previewComponent() {
        String format = formatField == null ? MinecraftChatBridge.KICK_DEFAULT_FORMAT : formatField.getValue();

        String platform = platformField == null ? "Kick" : platformField.getValue();

        return MinecraftChatBridge.buildIncomingComponent(format, platform, true, "ExampleUser", "Hello!");
    }

    private void release(Button button) {
        releaseFocus(button);

        if (getFocused() == button) {
            setFocused(null);
        }
    }

    /*
     * Screen
     */

    @Override
    public void onClose() {
        if (!setupMode) {
            saveSettings();
        }

        ScbScreens.open(parent);
    }

    @Override
    protected void renderContent(ScbGui graphics, int mouseX, int mouseY, float delta) {
        if (setupMode) {
            renderSetup(graphics);
        } else {
            renderSettings(graphics);
        }
    }

    private void renderSetup(ScbGui graphics) {
        int contentWidth = 400;
        int left = width / 2 - contentWidth / 2;

        Component setupTitle =
                ScbText.literal(
                        editingCredentials
                                ? "Kick App Credentials"
                                : "Kick Setup"
                );

        graphics.text(
                font,
                setupTitle,
                width / 2 - font.width(setupTitle) / 2,
                15,
                0xFF53FC18,
                true
        );

        graphics.text(
                font,
                "Kick requires a developer app before Stream Chat Bridge can connect.",
                left,
                36,
                0xFFAAAAAA,
                false
        );

        graphics.text(
                font,
                "1. Create a Kick developer app",
                left,
                62,
                0xFFFFFFFF,
                false
        );

        graphics.text(
                font,
                "2. Add this Redirect URL to your app",
                left,
                116,
                0xFFFFFFFF,
                false
        );

        graphics.text(
                font,
                KickAuth.getRedirectUri(),
                left,
                128,
                0xFFAAAAAA,
                false
        );

        graphics.text(
                font,
                "3. In the app's Permissions / Scopes section, enable:",
                left,
                174,
                0xFFFFFFFF,
                false
        );

        String configuredScopes = KickAuth.getRequiredScopes();

        if (configuredScopes != null && !configuredScopes.isBlank()) {
            String[] scopes = configuredScopes.trim().split("\\s+");

            for (int index = 0; index < scopes.length; index++) {
                String scope = scopes[index];
                int y = 190 + index * 14;

                graphics.text(font, scope, left + 10, y, 0xFFAAAAAA, false);
                graphics.text(font, scopeDescription(scope), left + 125, y, 0xFF777777, false);
            }
        }

        graphics.text(
                font,
                "4. Enter your app credentials",
                left,
                256,
                0xFFFFFFFF,
                false
        );

        graphics.text(
                font,
                "Client ID",
                left,
                272,
                0xFFAAAAAA,
                false
        );

        graphics.text(
                font,
                "Client Secret",
                left,
                317,
                0xFFAAAAAA,
                false
        );

        if (editingCredentials) {
            graphics.text(
                    font,
                    "Leave Client Secret blank to keep the existing credentials.",
                    left,
                    350,
                    0xFF777777,
                    false
            );
        }

        if (!statusMessage.isBlank()) {
            graphics.text(
                    font,
                    statusMessage,
                    width / 2 - font.width(statusMessage) / 2,
                    431,
                    0xFFAAAAAA,
                    false
            );
        }
    }

    private static String scopeDescription(String scope) {
        return switch (scope) {
            case "user:read" -> "Read your Kick account details";
            case "channel:read" -> "Look up channel information";
            case "chat:write" -> "Send messages to Kick chat";
            case "events:subscribe" -> "Receive live chat events";
            default -> "Required by Stream Chat Bridge";
        };
    }

    private void renderSettings(ScbGui graphics) {
        int contentWidth = 400;
        int left = width / 2 - contentWidth / 2;

        graphics.text(font, title, width / 2 - font.width(title) / 2, 20, 0xFF53FC18, true);

        graphics.text(font, "App", left, 42, 0xFFAAAAAA, false);

        graphics.text(font, "Configured", left + 35, 42, 0xFF55FF55, false);

        graphics.text(font, "Channel", left, 59, 0xFFAAAAAA, false);

        graphics.text(font, "Leave blank to use your own channel", left + 55, 59, 0xFF777777, false);

        graphics.text(font, "Outgoing Prefix", left, 99, 0xFFAAAAAA, false);

        graphics.text(font, "Platform Label", left, 139, 0xFFAAAAAA, false);

        graphics.text(font, "Incoming Format", left, 179, 0xFFAAAAAA, false);

        graphics.text(font, "Preview", left, 255, 0xFFAAAAAA, false);

        graphics.text(font, previewComponent(), left, 268, 0xFFFFFFFF, false);

        if (!statusMessage.isBlank()) {
            graphics.text(font, statusMessage, width / 2 - font.width(statusMessage) / 2, 385, 0xFFAAAAAA, false);
        }
    }

    private record ColorOption(String name, String tag, ChatFormatting formatting) {
    }
}
