package net.juniknytt.createrailgrinding;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue DEBUG_MODE = BUILDER
            .comment("Used for testing. Displays many values while rail grinding.")
            .define("debugMode", false);

    public static final ModConfigSpec.DoubleValue SOUND_VOLUME = BUILDER
            .comment("Volume for all rail-grinding sounds.")
            .defineInRange("soundVolume", 0.5, 0.0, 1.0);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}
}
