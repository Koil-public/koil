package com.spirit.koil.api.util.file.jar.booleans;

import net.fabricmc.loader.api.FabricLoader;

import static com.spirit.koil.api.util.file.jar.strings.ModIds.*;

public class ProjectPresentValue {
    public static boolean isTDBTDPresent() {
        return FabricLoader.getInstance().getModContainer(TDBTD_ID).isPresent();
    }
    public static boolean isShitpostPresent() {
        return FabricLoader.getInstance().getModContainer(SHIT_ID).isPresent();
    }
    public static boolean isIgnitePresent() {
        return FabricLoader.getInstance().getModContainer(IGNITE_ID).isPresent();
    }
    public static boolean isGamblicPresent() {
        return FabricLoader.getInstance().getModContainer(GAMBLIC_ID).isPresent();
    }
    public static boolean isRetromaniaPresent() {
        return FabricLoader.getInstance().getModContainer(RETROMANIA_ID).isPresent();
    }
    public static boolean isMilkedPresent() {
        return FabricLoader.getInstance().getModContainer(MILKED_ID).isPresent();
    }
    public static boolean isAnalieaPresent() {
        return FabricLoader.getInstance().getModContainer(ANALIEA_ID).isPresent();
    }
    public static boolean isBorderedPresent() {
        return FabricLoader.getInstance().getModContainer(BORDERED_ID).isPresent();
    }
    public static boolean isGrynnPresent() {
        return FabricLoader.getInstance().getModContainer(GRYNN_ID).isPresent();
    }
}
