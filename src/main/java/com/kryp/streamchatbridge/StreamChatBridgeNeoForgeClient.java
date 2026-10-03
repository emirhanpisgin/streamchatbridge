package com.kryp.streamchatbridge;

//? if neoforge {
/*import com.kryp.streamchatbridge.minecraft.MinecraftChatBridge;
import com.kryp.streamchatbridge.minecraft.StreamChatCommands;
import com.kryp.streamchatbridge.minecraft.StreamChatKeybinds;

import net.minecraft.client.Minecraft;

import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
*///?}
//? if neoforge && >=1.20.6 {
/*import net.neoforged.neoforge.client.event.ClientTickEvent;
*///?}
//? if neoforge && <1.20.6 {
/*import net.neoforged.neoforge.event.TickEvent;
*///?}

//? if neoforge {
/*public class StreamChatBridgeNeoForgeClient {

    public static void onClientSetup(FMLClientSetupEvent event) {
        StreamChatBridgeClient.initialize();
    }

    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(StreamChatKeybinds.create());
    }

    public static void onClientChat(ClientChatEvent event) {
        if (!MinecraftChatBridge.handleOutgoing(event.getMessage())) {
            event.setCanceled(true);
        }
    }

    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        StreamChatCommands.register(event.getDispatcher());
    }

    //? if >=1.20.6 {
    /^public static void onClientTick(ClientTickEvent.Post event) {
        StreamChatBridgeClient.onClientTick(Minecraft.getInstance());
    }
    ^///?} else {
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            StreamChatBridgeClient.onClientTick(Minecraft.getInstance());
        }
    }
    //?}
}
*///?}
