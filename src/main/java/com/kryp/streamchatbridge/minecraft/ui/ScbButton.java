package com.kryp.streamchatbridge.minecraft.ui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public final class ScbButton {

    private ScbButton() {
    }

    public static Builder builder(Component message, Button.OnPress onPress) {
        return new Builder(message, onPress);
    }

    public static final class Builder {

        private final Component message;

        private final Button.OnPress onPress;

        private int x;
        private int y;
        private int width;
        private int height;

        Builder(Component message, Button.OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;

            return this;
        }

        public Button build() {
            //? if >=1.19.3 {
            return Button.builder(message, onPress).bounds(x, y, width, height).build();
            //?} else {
            /*return new Button(x, y, width, height, message, onPress);
            *///?}
        }
    }
}
