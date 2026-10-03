package com.kryp.streamchatbridge.minecraft.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else if >=1.20 {
/*import net.minecraft.client.gui.GuiGraphics;
*///?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
*///?}

public final class ScbGui {

    //? if >=26.1 {
    private final GuiGraphicsExtractor graphics;

    public ScbGui(GuiGraphicsExtractor graphics) {
        this.graphics = graphics;
    }

    public void text(Font font, String text, int x, int y, int color, boolean shadow) {
        graphics.text(font, text, x, y, color, shadow);
    }

    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
        graphics.text(font, text, x, y, color, shadow);
    }
    //?} else if >=1.20 {
    /*private final GuiGraphics graphics;

    public ScbGui(GuiGraphics graphics) {
        this.graphics = graphics;
    }

    public void text(Font font, String text, int x, int y, int color, boolean shadow) {
        graphics.drawString(font, text, x, y, color, shadow);
    }

    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
        graphics.drawString(font, text, x, y, color, shadow);
    }
    *///?} else {
    /*private final PoseStack pose;

    public ScbGui(PoseStack pose) {
        this.pose = pose;
    }

    public void text(Font font, String text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            font.drawShadow(pose, text, x, y, color);
        } else {
            font.draw(pose, text, x, y, color);
        }
    }

    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            font.drawShadow(pose, text, x, y, color);
        } else {
            font.draw(pose, text, x, y, color);
        }
    }
    *///?}
}
