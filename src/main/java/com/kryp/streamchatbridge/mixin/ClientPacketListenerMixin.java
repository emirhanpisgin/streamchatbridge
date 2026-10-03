package com.kryp.streamchatbridge.mixin;

//? if fabric {
import net.minecraft.client.multiplayer.ClientPacketListener;

import org.spongepowered.asm.mixin.Mixin;
//?}
//? if fabric && <1.19 {
/*import com.kryp.streamchatbridge.minecraft.MinecraftChatBridge;

import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

//? if fabric {
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    //? if <1.19 {
    /*@Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
    private void streamchatbridge$interceptChat(String message, CallbackInfo callbackInfo) {
        if (!MinecraftChatBridge.handleOutgoing(message)) {
            callbackInfo.cancel();
        }
    }
    *///?}
}
//?}
