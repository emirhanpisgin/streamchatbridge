package com.kryp.streamchatbridge;

//? if neoforge {
/*import com.kryp.streamchatbridge.minecraft.MinecraftChatBridge;
import com.kryp.streamchatbridge.minecraft.StreamChatCommands;
import com.kryp.streamchatbridge.minecraft.StreamChatKeybinds;

import net.minecraft.client.Minecraft;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
*///?}

//? if neoforge {
/*@EventBusSubscriber(modid = StreamChatBridge.MOD_ID, value = Dist.CLIENT)
public class StreamChatBridgeNeoForgeClient {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        StreamChatBridgeClient.initialize();
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(StreamChatKeybinds.create());
    }

    @SubscribeEvent
    public static void onClientChat(ClientChatEvent event) {
        if (!MinecraftChatBridge.handleOutgoing(event.getMessage())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        StreamChatCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        StreamChatBridgeClient.onClientTick(Minecraft.getInstance());
    }
}
*///?}
