package com.kryp.streamchatbridge;

import net.minecraft.resources.Identifier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class StreamChatBridge {

    public static final String MOD_ID = "streamchatbridge";

    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    private StreamChatBridge() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
