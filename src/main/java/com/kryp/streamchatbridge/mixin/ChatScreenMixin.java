package com.kryp.streamchatbridge.mixin;

//? if fabric {
import net.minecraft.client.gui.screens.ChatScreen;

import org.spongepowered.asm.mixin.Mixin;
//?}
//? if fabric && >=1.19 && <1.19.3 {
/*import com.kryp.streamchatbridge.minecraft.MinecraftChatBridge;

import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}
//? if fabric && >=1.19.1 && <1.19.3 {
/*import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}

//? if fabric {
@Mixin(ChatScreen.class)
public class ChatScreenMixin {

    //? if >=1.19 && <1.19.1 {
    /*@Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void streamchatbridge$interceptChatInput(String message, boolean addToHistory, CallbackInfo callbackInfo) {
        if (!MinecraftChatBridge.handleOutgoing(message)) {
            callbackInfo.cancel();
        }
    }
    *///?}

    //? if >=1.19.1 && <1.19.3 {
    /*@Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void streamchatbridge$interceptChatInput(String message, boolean addToHistory, CallbackInfoReturnable<Boolean> callbackInfo) {
        if (!MinecraftChatBridge.handleOutgoing(message)) {
            callbackInfo.setReturnValue(true);

            callbackInfo.cancel();
        }
    }
    *///?}
}
//?}
