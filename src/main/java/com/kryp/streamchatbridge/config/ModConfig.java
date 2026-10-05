package com.kryp.streamchatbridge.config;

import java.util.ArrayList;
import java.util.List;

public class ModConfig {

    /*
     * Twitch
     */

    public boolean twitchSendEnabled = true;

    public String twitchChannel = "";

    public String twitchOutgoingPrefix = "!t ";

    public String twitchIncomingPlatformLabel = "Twitch";

    public String twitchIncomingMessageFormat = "<dark_purple>[{platform}]<reset> <green>{username}<reset>: <white>{message}";


    /*
     * Kick
     */

    public boolean kickSendEnabled = true;

    public String kickChannel = "";

    public String kickOutgoingPrefix = "!k ";

    public String kickIncomingPlatformLabel = "Kick";

    public String kickIncomingMessageFormat = "<green>[{platform}]<reset> <green>{username}<reset>: <white>{message}";

    /** Cached chatroom id for {@link #kickChatroomChannel} (avoids the lookup call). */
    public String kickChatroomId = "";

    public String kickChatroomChannel = "";

    /** Manual chatroom id override; used verbatim when set. */
    public String kickChatroomIdOverride = "";


    /*
     * Chat appearance
     */

    public boolean showUserColors = true;

    public boolean showBadges = true;

    public boolean highlightMentions = true;

    public boolean mentionSound = true;

    /** Usernames (case-insensitive) whose messages are not shown. */
    public List<String> ignoredUsers = new ArrayList<>();


    /*
     * Legacy settings
     *
     * Keep these temporarily so existing config files can be migrated
     * without immediately losing their old settings.
     */

    @Deprecated
    public String outgoingPrefix = null;

    @Deprecated
    public String incomingPlatformLabel = null;

    @Deprecated
    public String incomingMessageFormat = null;
}