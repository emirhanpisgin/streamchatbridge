package com.kryp.streamchatbridge.util;

import net.minecraft.util.Util;

import java.net.URI;

public final class BrowserUtils {

    private BrowserUtils() {
    }

    public static boolean open(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        try {
            Util.getPlatform().openUri(URI.create(url));

            return true;

        } catch (Exception e) {
            System.err.println("[Stream Chat Bridge] Could not open browser automatically: " + e.getMessage());

            return false;
        }
    }
}