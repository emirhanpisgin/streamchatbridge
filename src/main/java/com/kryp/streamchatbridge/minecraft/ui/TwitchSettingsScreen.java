package com.kryp.streamchatbridge.minecraft.ui;

import com.kryp.streamchatbridge.StreamChatBridgeClient;
import com.kryp.streamchatbridge.config.ConfigManager;
import com.kryp.streamchatbridge.config.ModConfig;
import com.kryp.streamchatbridge.minecraft.MinecraftChatBridge;
import com.kryp.streamchatbridge.twitch.TwitchAuth;
import com.kryp.streamchatbridge.util.Threads;
import com.kryp.streamchatbridge.twitch.TwitchClient;
import com.kryp.streamchatbridge.twitch.TwitchEventSubClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class TwitchSettingsScreen extends Screen {

    private static final ColorOption[] COLORS = {new ColorOption("Reset", "<reset>", null), new ColorOption("White", "<white>", ChatFormatting.WHITE), new ColorOption("Gray", "<gray>", ChatFormatting.GRAY), new ColorOption("Dark Gray", "<dark_gray>", ChatFormatting.DARK_GRAY), new ColorOption("Black", "<black>", ChatFormatting.BLACK), new ColorOption("Red", "<red>", ChatFormatting.RED), new ColorOption("Dark Red", "<dark_red>", ChatFormatting.DARK_RED), new ColorOption("Gold", "<gold>", ChatFormatting.GOLD), new ColorOption("Yellow", "<yellow>", ChatFormatting.YELLOW), new ColorOption("Green", "<green>", ChatFormatting.GREEN), new ColorOption("Dark Green", "<dark_green>", ChatFormatting.DARK_GREEN), new ColorOption("Aqua", "<aqua>", ChatFormatting.AQUA), new ColorOption("Dark Aqua", "<dark_aqua>", ChatFormatting.DARK_AQUA), new ColorOption("Blue", "<blue>", ChatFormatting.BLUE), new ColorOption("Dark Blue", "<dark_blue>", ChatFormatting.DARK_BLUE), new ColorOption("Light Purple", "<light_purple>", ChatFormatting.LIGHT_PURPLE), new ColorOption("Dark Purple", "<dark_purple>", ChatFormatting.DARK_PURPLE)};

    private final Screen parent;

    private EditBox channelField;
    private EditBox prefixField;
    private EditBox platformField;
    private EditBox formatField;

    private Button sendToggleButton;
    private Button colorButton;
    private Button saveButton;

    private int selectedColorIndex = 16;

    private String statusMessage = "";

    private boolean channelChangeInProgress;

    public TwitchSettingsScreen(Screen parent) {
        super(Component.literal("Twitch Settings"));

        this.parent = parent;
    }

    @Override
    protected void init() {
        ModConfig config = ConfigManager.get();

        int contentWidth = 400;
        int left = width / 2 - contentWidth / 2;

        channelField = new EditBox(font, left, 60, contentWidth, 20, Component.literal("Channel"));

        channelField.setValue(config.twitchChannel == null ? "" : config.twitchChannel);

        channelField.setMaxLength(50);

        addRenderableWidget(channelField);

        prefixField = new EditBox(font, left, 105, contentWidth, 20, Component.literal("Outgoing Prefix"));

        prefixField.setValue(config.twitchOutgoingPrefix == null ? "!t " : config.twitchOutgoingPrefix);

        prefixField.setMaxLength(30);

        addRenderableWidget(prefixField);

        platformField = new EditBox(font, left, 150, contentWidth, 20, Component.literal("Platform Label"));

        platformField.setValue(config.twitchIncomingPlatformLabel == null ? "Twitch" : config.twitchIncomingPlatformLabel);

        platformField.setMaxLength(30);

        addRenderableWidget(platformField);

        formatField = new EditBox(font, left, 195, contentWidth, 20, Component.literal("Incoming Format"));

        String format = config.twitchIncomingMessageFormat;

        if (format == null || format.isBlank()) {
            format = MinecraftChatBridge.TWITCH_DEFAULT_FORMAT;
        }

        formatField.setMaxLength(512);
        formatField.setValue(format);

        addRenderableWidget(formatField);

        colorButton = addRenderableWidget(Button.builder(selectedColorText(), button -> {
            selectedColorIndex++;

            if (selectedColorIndex >= COLORS.length) {
                selectedColorIndex = 0;
            }

            button.setMessage(selectedColorText());

            release(button);
        }).bounds(left, 230, 260, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Insert"), button -> {
            insertSelectedColor();

            release(button);
        }).bounds(left + 270, 230, 130, 20).build());

        sendToggleButton = addRenderableWidget(Button.builder(sendToggleText(), button -> {
            config.twitchSendEnabled = !config.twitchSendEnabled;

            button.setMessage(sendToggleText());

            release(button);
        }).bounds(left, 285, contentWidth, 20).build());

        saveButton = addRenderableWidget(Button.builder(Component.literal("Save"), button -> {
            save();

            release(button);
        }).bounds(left, 320, 195, 20).build());

        saveButton.active = !channelChangeInProgress;

        addRenderableWidget(Button.builder(Component.literal("Back"), button -> {
            save();

            release(button);

            ScbScreens.open(parent);
        }).bounds(left + 205, 320, 195, 20).build());
    }

    private Component sendToggleText() {
        return Component.literal("Minecraft → Twitch: " + (ConfigManager.get().twitchSendEnabled ? "ON" : "OFF"));
    }

    private Component selectedColorText() {
        ColorOption option = COLORS[selectedColorIndex];

        Component value = Component.literal(option.name());

        if (option.formatting() != null) {
            value = value.copy().withStyle(option.formatting());
        }

        return Component.literal("Color: ").append(value);
    }

    private void insertSelectedColor() {
        ColorOption option = COLORS[selectedColorIndex];

        String value = formatField.getValue();

        int cursor = Math.max(0, Math.min(formatField.getCursorPosition(), value.length()));

        String updated = value.substring(0, cursor) + option.tag() + value.substring(cursor);

        formatField.setValue(updated);

        formatField.setCursorPosition(cursor + option.tag().length());

        formatField.setFocused(true);
        setFocused(formatField);
    }

    private void save() {
        if (channelChangeInProgress) {
            return;
        }

        ModConfig config = ConfigManager.get();

        String oldChannel = normalizeChannel(config.twitchChannel);

        String newChannel = normalizeChannel(channelField.getValue());

        config.twitchOutgoingPrefix = prefixField.getValue();

        config.twitchIncomingPlatformLabel = platformField.getValue().isBlank() ? "Twitch" : platformField.getValue();

        config.twitchIncomingMessageFormat = formatField.getValue().isBlank() ? MinecraftChatBridge.TWITCH_DEFAULT_FORMAT : formatField.getValue();

        if (oldChannel.equalsIgnoreCase(newChannel)) {
            config.twitchChannel = newChannel;

            ConfigManager.save();

            statusMessage = "Saved";

            return;
        }

        TwitchAuth auth = StreamChatBridgeClient.getTwitchAuth();

        if (!auth.isAuthenticated()) {
            config.twitchChannel = newChannel;

            ConfigManager.save();

            statusMessage = "Saved";

            return;
        }

        /*
         * Keep the last valid channel in the config until Twitch confirms
         * that the requested channel exists.
         */
        config.twitchChannel = oldChannel;

        ConfigManager.save();

        applyChannel(newChannel);
    }

    private void applyChannel(String channel) {
        TwitchAuth auth = StreamChatBridgeClient.getTwitchAuth();

        if (!auth.isAuthenticated()) {
            return;
        }

        channelChangeInProgress = true;

        if (saveButton != null) {
            saveButton.active = false;
        }

        statusMessage = "Changing channel...";

        Threads.start("streamchatbridge-twitch-channel", () -> {
            TwitchClient twitchClient = StreamChatBridgeClient.getTwitchClient();

            TwitchEventSubClient eventSub = StreamChatBridgeClient.getTwitchEventSub();

            if (!twitchClient.setChannel(channel)) {
                setStatus("Channel not found");

                return;
            }

            eventSub.disconnect();

            eventSub.setChannelId(twitchClient.getChannelId());

            eventSub.connect();

            ModConfig config = ConfigManager.get();

            config.twitchChannel = normalizeChannel(channel);

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

    private Component previewComponent() {
        String format = formatField == null ? MinecraftChatBridge.TWITCH_DEFAULT_FORMAT : formatField.getValue();

        String platform = platformField == null ? "Twitch" : platformField.getValue();

        return MinecraftChatBridge.buildIncomingComponent(format, platform, "ExampleUser", "Hello!");
    }

    private void release(Button button) {
        button.setFocused(false);

        if (getFocused() == button) {
            setFocused(null);
        }
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

    @Override
    public void onClose() {
        save();

        ScbScreens.open(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int contentWidth = 400;
        int left = width / 2 - contentWidth / 2;

        graphics.text(font, title, width / 2 - font.width(title) / 2, 20, 0xFFAA55AA, true);

        graphics.text(font, "Channel", left, 49, 0xFFAAAAAA, false);

        graphics.text(font, "Outgoing Prefix", left, 94, 0xFFAAAAAA, false);

        graphics.text(font, "Platform Label", left, 139, 0xFFAAAAAA, false);

        graphics.text(font, "Incoming Format", left, 184, 0xFFAAAAAA, false);

        graphics.text(font, "Preview", left, 260, 0xFFAAAAAA, false);

        graphics.text(font, previewComponent(), left, 273, 0xFFFFFFFF, false);

        if (!statusMessage.isBlank()) {
            graphics.text(font, statusMessage, width / 2 - font.width(statusMessage) / 2, 355, 0xFFAAAAAA, false);
        }
    }

    private record ColorOption(String name, String tag, ChatFormatting formatting) {
    }
}
