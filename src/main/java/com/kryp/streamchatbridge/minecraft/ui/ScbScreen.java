package com.kryp.streamchatbridge.minecraft.ui;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else if >=1.20 {
/*import net.minecraft.client.gui.GuiGraphics;
*///?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
*///?}

public abstract class ScbScreen extends Screen {

    protected ScbScreen(Component title) {
        super(title);
    }

    //? if >=26.1 {
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        renderContent(new ScbGui(graphics), mouseX, mouseY, delta);
    }
    //?} else if >=1.20 {
    /*@Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);

        renderContent(new ScbGui(graphics), mouseX, mouseY, delta);
    }
    *///?} else {
    /*@Override
    public void render(PoseStack pose, int mouseX, int mouseY, float delta) {
        super.render(pose, mouseX, mouseY, delta);

        renderContent(new ScbGui(pose), mouseX, mouseY, delta);
    }
    *///?}

    protected abstract void renderContent(ScbGui graphics, int mouseX, int mouseY, float delta);

    protected void rebuild() {
        //? if >=1.19.4 {
        rebuildWidgets();
        //?} else {
        /*clearWidgets();
        init(minecraft, width, height);
        *///?}
    }

    protected void releaseFocus(AbstractWidget widget) {
        //? if >=1.19.4 {
        widget.setFocused(false);
        //?}
        setFocused(null);
    }

    protected void takeFocus(AbstractWidget widget) {
        setFocused(widget);

        //? if >=1.19.4 {
        widget.setFocused(true);
        //?}
    }
}
