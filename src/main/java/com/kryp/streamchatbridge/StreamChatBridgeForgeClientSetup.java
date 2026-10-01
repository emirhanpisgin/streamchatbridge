package com.kryp.streamchatbridge;

//? if forge {
/*import com.kryp.streamchatbridge.minecraft.StreamChatKeybinds;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
*///?}

//? if forge {
/*@Mod.EventBusSubscriber(modid = StreamChatBridge.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class StreamChatBridgeForgeClientSetup {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        StreamChatBridgeClient.initialize();
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(StreamChatKeybinds.create());
    }
}
*///?}
