package com.kryp.streamchatbridge.config;

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