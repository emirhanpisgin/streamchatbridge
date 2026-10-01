package com.kryp.streamchatbridge.minecraft;

import com.kryp.streamchatbridge.StreamChatBridgeClient;
import com.kryp.streamchatbridge.config.ConfigManager;
import com.kryp.streamchatbridge.config.ModConfig;
import com.kryp.streamchatbridge.kick.KickClient;
import com.kryp.streamchatbridge.twitch.TwitchClient;
import com.kryp.streamchatbridge.util.Threads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class MinecraftChatBridge {

    public static final String TWITCH_DEFAULT_FORMAT = "<dark_purple>[{platform}]<reset> <green>{username}<reset>: <white>{message}";

    public static final String KICK_DEFAULT_FORMAT = "<green>[{platform}]<reset> <green>{username}<reset>: <white>{message}";

    /*
     * Keep this alias for existing UI code until we replace the old
     * combined settings screen.
     */
    public static final String DEFAULT_FORMAT = TWITCH_DEFAULT_FORMAT;

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

    public static void showTwitchMessage(String username, String message) {
        ModConfig config = ConfigManager.get();

        String format = config.twitchIncomingMessageFormat;

        if (format == null || format.isBlank()) {

            format = TWITCH_DEFAULT_FORMAT;
        }

        String platform = config.twitchIncomingPlatformLabel;

        if (platform == null || platform.isBlank()) {

            platform = "Twitch";
        }

        showLocalMessage(buildIncomingComponent(format, platform, username, message));
    }

    /*
     * Incoming Kick
     */

    public static void showKickMessage(String username, String message) {
        ModConfig config = ConfigManager.get();

        String format = config.kickIncomingMessageFormat;

        if (format == null || format.isBlank()) {

            format = KICK_DEFAULT_FORMAT;
        }

        String platform = config.kickIncomingPlatformLabel;

        if (platform == null || platform.isBlank()) {

            platform = "Kick";
        }

        showLocalMessage(buildIncomingComponent(format, platform, username, message));
    }

    /*
     * Incoming message formatter
     */

    public static MutableComponent buildIncomingComponent(String format, String platform, String username, String message) {
        if (format == null || format.isBlank()) {

            format = DEFAULT_FORMAT;
        }

        if (platform == null || platform.isBlank()) {

            platform = "Twitch";
        }

        if (username == null) {
            username = "";
        }

        if (message == null) {
            message = "";
        }

        MutableComponent result = Component.empty();

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

                        appendFormattedText(result, format.substring(textStart, position), currentColor, platform, username, message);

                        currentColor = reset ? null : parsedColor;

                        position = closing + 1;

                        textStart = position;

                        continue;
                    }
                }
            }

            position++;
        }

        appendFormattedText(result, format.substring(textStart), currentColor, platform, username, message);

        return result;
    }

    private static void appendFormattedText(MutableComponent result, String text, ChatFormatting color, String platform, String username, String message) {
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
                appendPart(result, username, color);

                position = nextIndex + "{username}".length();

            } else {
                appendPart(result, message, color);

                position = nextIndex + "{message}".length();
            }
        }
    }

    private static void appendPart(MutableComponent result, String text, ChatFormatting color) {
        if (text.isEmpty()) {
            return;
        }

        MutableComponent component = Component.literal(text);

        if (color != null) {
            component.withStyle(color);
        }

        result.append(component);
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
        return Component.literal("[Stream Chat Bridge] ").withStyle(ChatFormatting.DARK_GRAY);
    }

    public static MutableComponent twitch() {
        return Component.literal("Twitch").withStyle(ChatFormatting.DARK_PURPLE);
    }

    public static MutableComponent kick() {
        return Component.literal("Kick").withStyle(ChatFormatting.GREEN);
    }

    public static MutableComponent label(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    public static MutableComponent value(String text) {
        return Component.literal(text).withStyle(ChatFormatting.WHITE);
    }

    public static MutableComponent success(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GREEN);
    }

    public static MutableComponent warning(String text) {
        return Component.literal(text).withStyle(ChatFormatting.YELLOW);
    }

    public static MutableComponent error(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }

    public static MutableComponent separator(String text) {
        return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY);
    }

    public static void showLocalMessage(String message) {
        showLocalMessage(Component.literal(message));
    }

    public static void showLocalMessage(Component message) {
        Minecraft client = Minecraft.getInstance();

        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(message);
            }
        });
    }
}