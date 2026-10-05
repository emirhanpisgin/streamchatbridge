package com.kryp.streamchatbridge.chat;

/**
 * Loader-agnostic incoming chat message with optional presentation extras
 * (user color, badges). The platform clients build these; the Minecraft layer
 * renders them.
 */
public final class PlatformChatMessage {

    public static final int NO_COLOR = -1;

    private final String username;
    private final String message;
    private final int color;
    private final String badges;

    public PlatformChatMessage(String username, String message, int color, String badges) {
        this.username = username == null ? "" : username;
        this.message = message == null ? "" : message;
        this.color = color;
        this.badges = badges == null ? "" : badges;
    }

    public String getUsername() {
        return username;
    }

    public String getMessage() {
        return message;
    }

    /** RGB color from the platform, or {@link #NO_COLOR}. */
    public int getColor() {
        return color;
    }

    /** Space-separated short badge tags (e.g. {@code "broadcaster mod vip"}), or empty. */
    public String getBadges() {
        return badges;
    }

    /** Maps a platform badge set id to a short display tag ("" when unknown). */
    public static String badgeTag(String setId) {
        if (setId == null) {
            return "";
        }

        switch (setId) {
            case "broadcaster":
                return "broadcaster";
            case "moderator":
                return "mod";
            case "vip":
                return "vip";
            case "subscriber":
                return "sub";
            case "founder":
                return "founder";
            default:
                return "";
        }
    }

    /** Parses a {@code #RRGGBB} color string, returning {@link #NO_COLOR} when unusable. */
    public static int parseColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return NO_COLOR;
        }

        String hex = raw.startsWith("#") ? raw.substring(1) : raw;

        try {
            return Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return NO_COLOR;
        }
    }
}
