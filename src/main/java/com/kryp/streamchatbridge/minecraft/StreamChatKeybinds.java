package com.kryp.streamchatbridge.minecraft;

import com.kryp.streamchatbridge.StreamChatBridge;
import com.kryp.streamchatbridge.minecraft.ui.ScbScreens;
import com.kryp.streamchatbridge.minecraft.ui.StreamChatConfigScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

//? if fabric && >=26.1 {
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//?} else if fabric {
/*import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
*///?}

public final class StreamChatKeybinds {

    private static final int DEFAULT_KEY = InputConstants.getKey("key.keyboard.f8").getValue();

    private static KeyMapping openDashboard;

    private StreamChatKeybinds() {
    }

    public static void register() {
        //? if fabric && >=26.1 {
        openDashboard = KeyMappingHelper.registerKeyMapping(create());
        //?} else if fabric {
        /*openDashboard = KeyBindingHelper.registerKeyBinding(create());
        */        //?} else if forge && >=1.18 && <1.19 {
        /*openDashboard = create();
        net.minecraftforge.client.ClientRegistry.registerKeyBinding(openDashboard);
        *///?} else if forge && <1.18 {
        /*openDashboard = create();

        net.minecraftforge.fmlclient.registry.ClientRegistry.registerKeyBinding(openDashboard);
        *///?}
    }

    public static KeyMapping create() {
        if (openDashboard == null) {
            //? if >=1.21.9 {
            KeyMapping.Category category = KeyMapping.Category.register(StreamChatBridge.id("main"));
            //?} else {
            /*String category = "key.categories.streamchatbridge.main";
            *///?}

            //? if >=26.3 {
            /*openDashboard = new KeyMapping("key.streamchatbridge.open_dashboard", InputConstants.Type.KEYBOARD, DEFAULT_KEY, category);
            *///?} else {
            openDashboard = new KeyMapping("key.streamchatbridge.open_dashboard", InputConstants.Type.KEYSYM, DEFAULT_KEY, category);
            //?}
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
