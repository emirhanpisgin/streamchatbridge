package com.kryp.streamchatbridge.minecraft;

import com.kryp.streamchatbridge.StreamChatBridge;
import com.kryp.streamchatbridge.minecraft.ui.ScbScreens;
import com.kryp.streamchatbridge.minecraft.ui.StreamChatConfigScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

//? if fabric {
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//?}

public final class StreamChatKeybinds {

    private static final int DEFAULT_KEY = GLFW.GLFW_KEY_F8;

    private static KeyMapping openDashboard;

    private StreamChatKeybinds() {
    }

    public static void register() {
        //? if fabric {
        openDashboard = KeyMappingHelper.registerKeyMapping(create());
        //?}
    }

    public static KeyMapping create() {
        if (openDashboard == null) {
            KeyMapping.Category category = KeyMapping.Category.register(StreamChatBridge.id("main"));

            openDashboard = new KeyMapping("key.streamchatbridge.open_dashboard", InputConstants.Type.KEYSYM, DEFAULT_KEY, category);
        }

        return openDashboard;
    }

    public static void handleTick(Minecraft client) {
        if (openDashboard == null) {
            return;
        }

        while (openDashboard.consumeClick()) {
            ScbScreens.open(new StreamChatConfigScreen(null));
        }
    }
}
