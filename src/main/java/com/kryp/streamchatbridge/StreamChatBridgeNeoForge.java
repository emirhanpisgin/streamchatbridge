package com.kryp.streamchatbridge;

//? if neoforge {
/*import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
*///?}

//? if neoforge {
/*@Mod(StreamChatBridge.MOD_ID)
public class StreamChatBridgeNeoForge {

    public StreamChatBridgeNeoForge(IEventBus modBus) {
        StreamChatBridge.LOGGER.info("Stream Chat Bridge initialized");

        //? if >=1.21.9 {
        /^if (FMLEnvironment.getDist() == Dist.CLIENT) {
            registerClientListeners(modBus);
        }
        ^///?} else {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            registerClientListeners(modBus);
        }
        //?}
    }

    private static void registerClientListeners(IEventBus modBus) {
        modBus.addListener(StreamChatBridgeNeoForgeClient::onClientSetup);
        modBus.addListener(StreamChatBridgeNeoForgeClient::onRegisterKeyMappings);

        NeoForge.EVENT_BUS.addListener(StreamChatBridgeNeoForgeClient::onClientChat);
        NeoForge.EVENT_BUS.addListener(StreamChatBridgeNeoForgeClient::onRegisterClientCommands);
        NeoForge.EVENT_BUS.addListener(StreamChatBridgeNeoForgeClient::onClientTick);
    }
}
*///?}
