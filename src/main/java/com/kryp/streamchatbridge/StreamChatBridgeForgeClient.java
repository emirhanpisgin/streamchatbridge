package com.kryp.streamchatbridge;

//? if forge {
/*import com.kryp.streamchatbridge.minecraft.MinecraftChatBridge;
import com.kryp.streamchatbridge.minecraft.StreamChatCommands;
import com.kryp.streamchatbridge.minecraft.StreamChatKeybinds;

import net.minecraft.client.Minecraft;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
*///?}
//? if forge && >=1.19 {
/*import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
*///?}
//? if forge && <1.21.6 {
/*import net.minecraftforge.eventbus.api.SubscribeEvent;
*///?}
//? if forge && >=1.21.6 {
/*import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
*///?}

//? if forge {
/*@Mod.EventBusSubscriber(modid = StreamChatBridge.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class StreamChatBridgeForgeClient {

    //? if >=1.21.6 {
    @SubscribeEvent
    public static boolean onClientChat(ClientChatEvent event) {
        return !MinecraftChatBridge.handleOutgoing(event.getMessage());
    }
    //?} else {
    /^@SubscribeEvent
    public static void onClientChat(ClientChatEvent event) {
        //? if <1.19 {
        if (event.getMessage().startsWith("/scb") && StreamChatCommands.handleLocalCommand(event.getMessage())) {
            event.setCanceled(true);

            return;
        }
        //?}

        if (!MinecraftChatBridge.handleOutgoing(event.getMessage())) {
            event.setCanceled(true);
        }
    }
    ^///?}

    //? if >=1.19 {
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        StreamChatCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(StreamChatKeybinds.create());
    }
    //?}

    //? if >=1.21.6 {
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        StreamChatBridgeClient.onClientTick(Minecraft.getInstance());
    }
    //?} else {
    /^@SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            StreamChatBridgeClient.onClientTick(Minecraft.getInstance());
        }
    }
    ^///?}
}
*///?}
