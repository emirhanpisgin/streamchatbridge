package com.kryp.streamchatbridge.util;

import net.minecraft.network.chat.MutableComponent;

//? if >=1.19 {
import net.minecraft.network.chat.Component;
//?} else {
/*import net.minecraft.network.chat.TextComponent;
*///?}

public final class ScbText {

    private ScbText() {
    }

    public static MutableComponent literal(String text) {
        //? if >=1.19 {
        return Component.literal(text);
        //?} else {
        /*return new TextComponent(text);
        *///?}
    }

    public static MutableComponent empty() {
        //? if >=1.19 {
        return Component.empty();
        //?} else {
        /*return new TextComponent("");
        *///?}
    }
}
