package com.kryp.streamchatbridge.util;

import com.kryp.streamchatbridge.StreamChatBridge;

//? if >=1.21.11 {
import net.minecraft.util.Util;
//?} else {
/*import net.minecraft.Util;
*///?}

import java.net.URI;

public final class BrowserUtils {

    private BrowserUtils() {
    }

    public static boolean open(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        try {
            //? if >=26.3 {
            /*com.mojang.blaze3d.Blaze3D.openUri(URI.create(url));
            *///?} else {
            Util.getPlatform().openUri(URI.create(url));
            //?}

            return true;

        } catch (Exception e) {
            StreamChatBridge.LOGGER.warn("[Stream Chat Bridge] Could not open browser automatically: " + e.getMessage());

            return false;
        }
    }
}
