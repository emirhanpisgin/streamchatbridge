package com.kryp.streamchatbridge;

//? if forge {
/*import com.kryp.streamchatbridge.minecraft.MinecraftChatBridge;
import com.kryp.streamchatbridge.minecraft.StreamChatCommands;
import com.kryp.streamchatbridge.minecraft.StreamChatKeybinds;

import net.minecraft.client.Minecraft;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
*///?}

//? if forge {
/*@Mod.EventBusSubscriber(modid = StreamChatBridge.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class StreamChatBridgeForgeClient {

    @SubscribeEvent
    public static boolean onClientChat(ClientChatEvent event) {
        return !MinecraftChatBridge.handleOutgoing(event.getMessage());
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        StreamChatCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(StreamChatKeybinds.create());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        StreamChatBridgeClient.onClientTick(Minecraft.getInstance());
    }
}
*///?}
