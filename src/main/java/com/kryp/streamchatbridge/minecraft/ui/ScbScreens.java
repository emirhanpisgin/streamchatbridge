package com.kryp.streamchatbridge.minecraft.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class ScbScreens {

    private ScbScreens() {
    }

    public static void open(Screen screen) {
        Minecraft minecraft = Minecraft.getInstance();

        //? if >=26.2 {
        minecraft.gui.setScreen(screen);
        //?} else {
        /*minecraft.setScreen(screen);
        *///?}
    }
}
