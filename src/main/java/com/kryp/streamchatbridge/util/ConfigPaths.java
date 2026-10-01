package com.kryp.streamchatbridge.util;

import java.nio.file.Path;

public final class ConfigPaths {

    private ConfigPaths() {
    }

    public static Path configDir() {
        //? if fabric {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        //?} else if forge {
        /*return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
        *///?} else {
        /*return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
        *///?}
    }
}
