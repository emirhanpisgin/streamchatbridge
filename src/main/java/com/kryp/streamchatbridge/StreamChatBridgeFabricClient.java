package com.kryp.streamchatbridge;

//? if fabric {
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//?}

//? if fabric {
@Environment(EnvType.CLIENT)
public class StreamChatBridgeFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        StreamChatBridgeClient.initialize();

        ClientTickEvents.END_CLIENT_TICK.register(StreamChatBridgeClient::onClientTick);
    }
}
//?}
