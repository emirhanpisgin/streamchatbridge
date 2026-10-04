package com.kryp.streamchatbridge.mixin;

//? if fabric {
import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
//?}
//? if fabric && <1.19 {
/*import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

//? if fabric {
@Mixin(Minecraft.class)
public class DevAutoJoinMixin {

    //? if <1.19 {
    /*@Inject(method = "tick", at = @At("TAIL"))
    private void streamchatbridge$autoJoin(CallbackInfo callbackInfo) {
        String target = System.getProperty("scb.devJoin");

        if (target == null || System.getProperty("fabric.development") == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        // The title screen exists while the initial resource/model bake is still
        // running on 1.17/1.18; joining then crashes chunk rendering with a null
        // BakedModel. Wait until the loading overlay is gone.
        if (minecraft.getOverlay() != null) {
            return;
        }

        Screen screen = minecraft.screen;

        if (!(screen instanceof TitleScreen)) {
            return;
        }

        ServerAddress address = ServerAddress.parseString(target);

        ConnectScreen.startConnecting(screen, minecraft, address, new ServerData("scb", target, false));
    }
    *///?}
}
//?}
