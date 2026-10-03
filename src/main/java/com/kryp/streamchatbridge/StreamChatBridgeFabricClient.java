package com.kryp.streamchatbridge;

//? if fabric {
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//?}
//? if fabric && >=1.19.3 {
import com.kryp.streamchatbridge.minecraft.MinecraftChatBridge;

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
//?}

//? if fabric {
@Environment(EnvType.CLIENT)
public class StreamChatBridgeFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        StreamChatBridgeClient.initialize();

        ClientTickEvents.END_CLIENT_TICK.register(StreamChatBridgeClient::onClientTick);

        //? if >=1.19.3 {
        ClientSendMessageEvents.ALLOW_CHAT.register(MinecraftChatBridge::handleOutgoing);
        //?}
    }
}
//?}
