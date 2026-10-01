package com.kryp.streamchatbridge;

//? if forge {
/*import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
*///?}

//? if forge {
/*@Mod(StreamChatBridge.MOD_ID)
public class StreamChatBridgeForge {

    public StreamChatBridgeForge() {
        StreamChatBridge.LOGGER.info("Stream Chat Bridge initialized");

        if (FMLEnvironment.dist == Dist.CLIENT) {
            StreamChatBridgeClient.initialize();
        }
    }
}
*///?}
