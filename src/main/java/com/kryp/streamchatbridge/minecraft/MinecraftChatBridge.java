package com.kryp.streamchatbridge.minecraft;

import com.kryp.streamchatbridge.StreamChatBridge;
import com.kryp.streamchatbridge.StreamChatBridgeClient;
import com.kryp.streamchatbridge.chat.PlatformChatMessage;
import com.kryp.streamchatbridge.config.ConfigManager;
import com.kryp.streamchatbridge.config.ModConfig;
import com.kryp.streamchatbridge.kick.KickClient;
import com.kryp.streamchatbridge.twitch.TwitchClient;
import com.kryp.streamchatbridge.util.ScbText;
import com.kryp.streamchatbridge.util.Threads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
//? if >=1.21.9 {
import net.minecraft.network.chat.FontDescription;
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvents;

import java.util.Locale;

public final class MinecraftChatBridge {

    public static final String TWITCH_DEFAULT_FORMAT = "<dark_purple>[{platform}]<reset> <green>{username}<reset>: <white>{message}";

    public static final String KICK_DEFAULT_FORMAT = "<green>[{platform}]<reset> <green>{username}<reset>: <white>{message}";

    /*
     * Keep this alias for existing UI code until we replace the old
     * combined settings screen.
     */
    public static final String DEFAULT_FORMAT = TWITCH_DEFAULT_FORMAT;

    /** Bitmap-font style for badge icons (see the font resource). */
    private static final Style BADGE_FONT_STYLE = createBadgeFontStyle();

    private static Style createBadgeFontStyle() {
        //? if >=1.21.9 {
        return Style.EMPTY.withFont(new FontDescription.Resource(StreamChatBridge.id("badges")));
        //?} else {
        /*return Style.EMPTY.withFont(StreamChatBridge.id("badges"));
        *///?}
    }

    private MinecraftChatBridge() {
    }

    /*
     * Outgoing Minecraft chat
     */

    public static boolean handleOutgoing(String message) {
        ModConfig config = ConfigManager.get();

        /*
         * Twitch
         */

        if (config.twitchSendEnabled) {
            String prefix = normalizePrefix(config.twitchOutgoingPrefix);

            if (matchesPrefix(message, prefix)) {
                String outgoingMessage = stripPrefix(message, prefix);

                if (!outgoingMessage.isEmpty()) {
                    TwitchClient twitchClient = StreamChatBridgeClient.getTwitchClient();

                    Threads.start("streamchatbridge-twitch-send", () -> sendTwitchMessage(twitchClient, outgoingMessage));
                }

                return false;
            }
        }

        /*
         * Kick
         */

        if (config.kickSendEnabled) {
            String prefix = normalizePrefix(config.kickOutgoingPrefix);

            if (matchesPrefix(message, prefix)) {
                String outgoingMessage = stripPrefix(message, prefix);

                if (!outgoingMessage.isEmpty()) {
                    KickClient kickClient = StreamChatBridgeClient.getKickClient();

                    Threads.start("streamchatbridge-kick-send", () -> sendKickMessage(kickClient, outgoingMessage));
                }

                return false;
            }
        }

        /*
         * No bridge prefix matched.
         * Send the message to Minecraft normally.
         */

        return true;
    }

    private static void sendTwitchMessage(TwitchClient twitchClient, String message) {
        boolean sent = twitchClient.sendMessage(message);

        if (!sent) {
            showLocalMessage(systemMessage().append(twitch()).append(separator(": ")).append(error("Failed to send message")));
        }
    }

    private static void sendKickMessage(KickClient kickClient, String message) {
        boolean sent = kickClient.sendMessage(message);

        if (!sent) {
            showLocalMessage(systemMessage().append(kick()).append(separator(": ")).append(error("Failed to send message")));
        }
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }

        return prefix;
    }

    private static boolean matchesPrefix(String message, String prefix) {
        if (message == null || prefix == null || prefix.isEmpty()) {

            return false;
        }

        return message.startsWith(prefix);
    }

    private static String stripPrefix(String message, String prefix) {
        return message.substring(prefix.length()).trim();
    }

    /*
     * Incoming Twitch
     */

    public static void showTwitchMessage(PlatformChatMessage chat) {
        ModConfig config = ConfigManager.get();

        String format = config.twitchIncomingMessageFormat;

        if (format == null || format.isBlank()) {

            format = TWITCH_DEFAULT_FORMAT;
        }

        String platform = config.twitchIncomingPlatformLabel;

        if (platform == null || platform.isBlank()) {

            platform = "Twitch";
        }

        showIncoming(chat, format, platform);
    }

    /*
     * Incoming Kick
     */

    public static void showKickMessage(PlatformChatMessage chat) {
        ModConfig config = ConfigManager.get();

        String format = config.kickIncomingMessageFormat;

        if (format == null || format.isBlank()) {

            format = KICK_DEFAULT_FORMAT;
        }

        String platform = config.kickIncomingPlatformLabel;

        if (platform == null || platform.isBlank()) {

            platform = "Kick";
        }

        showIncoming(chat, format, platform);
    }

    private static void showIncoming(PlatformChatMessage chat, String format, String platform) {
        ModConfig config = ConfigManager.get();

        if (isIgnored(config, chat.getUsername())) {
            return;
        }

        boolean mentioned = isMentioned(chat.getMessage());

        showLocalMessage(buildIncomingComponent(format, platform, chat, mentioned));

        if (mentioned && config.mentionSound) {
            playMentionSound();
        }
    }

    private static boolean isIgnored(ModConfig config, String username) {
        if (config.ignoredUsers == null || config.ignoredUsers.isEmpty() || username == null || username.isBlank()) {

            return false;
        }

        for (String ignored : config.ignoredUsers) {
            if (ignored != null && ignored.trim().equalsIgnoreCase(username.trim())) {

                return true;
            }
        }

        return false;
    }

    private static boolean isMentioned(String message) {
        if (message == null || message.isBlank()) {

            return false;
        }

        Minecraft client = Minecraft.getInstance();

        if (client.getUser() == null) {

            return false;
        }

        String player = client.getUser().getName();

        return player != null && !player.isBlank() && message.toLowerCase(Locale.ROOT).contains(player.toLowerCase(Locale.ROOT));
    }

    private static void playMentionSound() {
        try {
            Minecraft client = Minecraft.getInstance();

            client.execute(() -> client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.0F)));
        } catch (Exception ignored) {
        }
    }

    /*
     * Incoming message formatter
     */

    public static MutableComponent buildIncomingComponent(String format, String platform, String username, String message) {
        return buildIncomingComponent(format, platform, new PlatformChatMessage(username, message, PlatformChatMessage.NO_COLOR, ""), false);
    }

    public static MutableComponent buildIncomingComponent(String format, String platform, PlatformChatMessage chat, boolean mentioned) {
        if (format == null || format.isBlank()) {

            format = DEFAULT_FORMAT;
        }

        if (platform == null || platform.isBlank()) {

            platform = "Twitch";
        }

        MutableComponent result = ScbText.empty();

        ChatFormatting currentColor = null;

        int position = 0;
        int textStart = 0;

        while (position < format.length()) {
            if (format.charAt(position) == '<') {
                int closing = format.indexOf('>', position + 1);

                if (closing != -1) {
                    String tag = format.substring(position + 1, closing);

                    ChatFormatting parsedColor = parseColorTag(tag);

                    boolean reset = tag.equalsIgnoreCase("reset");

                    if (parsedColor != null || reset) {

                        appendFormattedText(result, format.substring(textStart, position), currentColor, platform, chat, mentioned);

                        currentColor = reset ? null : parsedColor;

                        position = closing + 1;

                        textStart = position;

                        continue;
                    }
                }
            }

            position++;
        }

        appendFormattedText(result, format.substring(textStart), currentColor, platform, chat, mentioned);

        return result;
    }

    private static void appendFormattedText(MutableComponent result, String text, ChatFormatting color, String platform, PlatformChatMessage chat, boolean mentioned) {
        ModConfig config = ConfigManager.get();

        int position = 0;

        while (position < text.length()) {
            int platformIndex = text.indexOf("{platform}", position);

            int usernameIndex = text.indexOf("{username}", position);

            int messageIndex = text.indexOf("{message}", position);

            int nextIndex = firstIndex(platformIndex, usernameIndex, messageIndex);

            if (nextIndex == -1) {
                appendPart(result, text.substring(position), color);

                return;
            }

            if (nextIndex > position) {
                appendPart(result, text.substring(position, nextIndex), color);
            }

            if (nextIndex == platformIndex) {
                appendPart(result, platform, color);

                position = nextIndex + "{platform}".length();

            } else if (nextIndex == usernameIndex) {
                if (config.showBadges) {
                    appendBadges(result, chat.getBadges(), config.staffBadgesOnly);
                }

                if (config.showUserColors && chat.getColor() != PlatformChatMessage.NO_COLOR) {
                    appendPart(result, chat.getUsername(), chat.getColor());
                } else {
                    appendPart(result, chat.getUsername(), color);
                }

                position = nextIndex + "{username}".length();

            } else {
                ChatFormatting messageColor = mentioned && config.highlightMentions ? ChatFormatting.GOLD : color;

                appendPart(result, chat.getMessage(), messageColor);

                position = nextIndex + "{message}".length();
            }
        }
    }

    private static void appendBadges(MutableComponent result, String badges, boolean staffOnly) {
        if (badges == null || badges.isBlank()) {

            return;
        }

        for (String badge : badges.split(" ")) {
            if (staffOnly && (badge.equals("sub") || badge.equals("founder"))) {

                continue;
            }

            char glyph = badgeGlyph(badge);

            if (glyph == 0) {

                continue;
            }

            appendPart(result, String.valueOf(glyph), BADGE_FONT_STYLE);

            appendPart(result, " ", (ChatFormatting) null);
        }
    }

    /** Badge icons are a bitmap font; see {@code assets/streamchatbridge/font/badges.json}. */
    private static char badgeGlyph(String badge) {
        return switch (badge) {
            case "broadcaster" -> '\uE000';

            case "mod" -> '\uE001';

            case "vip" -> '\uE002';

            case "sub" -> '\uE003';

            case "founder" -> '\uE004';

            default -> 0;
        };
    }

    private static void appendPart(MutableComponent result, String text, ChatFormatting color) {
        if (text.isEmpty()) {
            return;
        }

        MutableComponent component = ScbText.literal(sanitize(text));

        if (color != null) {
            component.withStyle(color);
        }

        result.append(component);
    }

    private static void appendPart(MutableComponent result, String text, int rgbColor) {
        if (text.isEmpty()) {
            return;
        }

        MutableComponent component = ScbText.literal(sanitize(text));

        component.withStyle(style -> style.withColor(TextColor.fromRgb(rgbColor)));

        result.append(component);
    }

    private static void appendPart(MutableComponent result, String text, Style style) {
        if (text.isEmpty()) {
            return;
        }

        MutableComponent component = ScbText.literal(sanitize(text));

        if (style != null) {
            component.withStyle(style);
        }

        result.append(component);
    }

    /**
     * Minecraft renders {@code §} formatting codes even inside literal text, so
     * viewer-supplied names and messages must not be able to inject styles.
     */
    private static String sanitize(String text) {
        if (text.indexOf('\u00A7') < 0) {
            return text;
        }

        return text.replace("\u00A7", "");
    }

    private static int firstIndex(int... indexes) {
        int result = -1;

        for (int index : indexes) {
            if (index >= 0 && (result == -1 || index < result)) {

                result = index;
            }
        }

        return result;
    }

    private static ChatFormatting parseColorTag(String tag) {
        if (tag == null || tag.isBlank()) {

            return null;
        }

        return switch (tag.toLowerCase()) {
            case "black" -> ChatFormatting.BLACK;

            case "dark_blue" -> ChatFormatting.DARK_BLUE;

            case "dark_green" -> ChatFormatting.DARK_GREEN;

            case "dark_aqua" -> ChatFormatting.DARK_AQUA;

            case "dark_red" -> ChatFormatting.DARK_RED;

            case "dark_purple" -> ChatFormatting.DARK_PURPLE;

            case "gold" -> ChatFormatting.GOLD;

            case "gray" -> ChatFormatting.GRAY;

            case "dark_gray" -> ChatFormatting.DARK_GRAY;

            case "blue" -> ChatFormatting.BLUE;

            case "green" -> ChatFormatting.GREEN;

            case "aqua" -> ChatFormatting.AQUA;

            case "red" -> ChatFormatting.RED;

            case "light_purple" -> ChatFormatting.LIGHT_PURPLE;

            case "yellow" -> ChatFormatting.YELLOW;

            case "white" -> ChatFormatting.WHITE;

            default -> null;
        };
    }

    /*
     * Stream Chat Bridge system message components
     */

    public static MutableComponent systemMessage() {
        return ScbText.literal("[Stream Chat Bridge] ").withStyle(ChatFormatting.DARK_GRAY);
    }

    public static MutableComponent twitch() {
        return ScbText.literal("Twitch").withStyle(ChatFormatting.DARK_PURPLE);
    }

    public static MutableComponent kick() {
        return ScbText.literal("Kick").withStyle(ChatFormatting.GREEN);
    }

    public static MutableComponent label(String text) {
        return ScbText.literal(text).withStyle(ChatFormatting.GRAY);
    }

    public static MutableComponent value(String text) {
        return ScbText.literal(text).withStyle(ChatFormatting.WHITE);
    }

    public static MutableComponent success(String text) {
        return ScbText.literal(text).withStyle(ChatFormatting.GREEN);
    }

    public static MutableComponent warning(String text) {
        return ScbText.literal(text).withStyle(ChatFormatting.YELLOW);
    }

    public static MutableComponent error(String text) {
        return ScbText.literal(text).withStyle(ChatFormatting.RED);
    }

    public static MutableComponent separator(String text) {
        return ScbText.literal(text).withStyle(ChatFormatting.DARK_GRAY);
    }

    public static void showLocalMessage(String message) {
        showLocalMessage(ScbText.literal(message));
    }

    public static void showLocalMessage(Component message) {
        Minecraft client = Minecraft.getInstance();

        client.execute(() -> {
            //? if >=26.1 {
            if (client.player != null) {
                client.player.sendSystemMessage(message);
            }
            //?} else if >=1.21.2 {
            /*client.gui.getChat().addMessage(message);
            *///?} else if >=1.19 {
            /*if (client.player != null) {
                client.player.sendSystemMessage(message);
            }
            *///?} else {
            /*client.gui.getChat().addMessage(message);
            *///?}
        });
    }
}