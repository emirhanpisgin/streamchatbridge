package com.kryp.streamchatbridge;

//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class StreamChatBridge {

    public static final String MOD_ID = "streamchatbridge";

    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    private StreamChatBridge() {
    }

    //? if >=1.21.11 {
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
    //?} else if >=1.21 {
    /*public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
    *///?} else {
    /*public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
    *///?}
}
