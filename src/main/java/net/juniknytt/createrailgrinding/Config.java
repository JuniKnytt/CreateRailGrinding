package net.juniknytt.createrailgrinding;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue DEBUG_MODE = CLIENT_BUILDER
            .comment("Used for testing. Displays many values while rail grinding.")
            .define("debugMode", false);

    public static final ModConfigSpec.DoubleValue SOUND_VOLUME = CLIENT_BUILDER
            .comment("Volume for all rail-grinding sounds.")
            .defineInRange("soundVolume", 0.5, 0.0, 1.0);

    public static final ModConfigSpec SPEC = CLIENT_BUILDER.build();

    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue TOP_GRIND_SPEED = SERVER_BUILDER
            .comment("Top speed while accelerating with sneak.")
            .defineInRange("topGrindSpeed", 0.75, 0.1, 1.0);

    public static final ModConfigSpec.DoubleValue CRUISE_GRIND_SPEED = SERVER_BUILDER
            .comment("Cruising speed without accelerating with sneak.")
            .defineInRange("cruiseGrindSpeed", 0.75, 0.1, 2.0);

    public static final ModConfigSpec.DoubleValue SNEAK_ACCELERATION = SERVER_BUILDER
            .comment("Acceleration while sneaking. Multiplier applied to base acceleration when shift is held.")
            .defineInRange("sneakAcceleration", 1.0, 0.1, 2.0);

    public static final ModConfigSpec.DoubleValue DOWNWARD_MOMENTUM_GAIN = SERVER_BUILDER
            .comment("Acceleration multiplier grinding down inclines.")
            .defineInRange("downwardMomentumGain", 0.5, 0.1, 2.0);

    public static final ModConfigSpec.DoubleValue RAIL_JUMP_CHARGE = SERVER_BUILDER
            .comment("How far you'll leap based on how long you charge your jump. Scales both horizontal and vertical charge bonus.")
            .defineInRange("railJumpCharge", 0.5, 0.1, 1.0);

    public static final ModConfigSpec.DoubleValue RAIL_JUMP_MOMENTUM = SERVER_BUILDER
            .comment("How far you'll leap based speed. Scales both horizontal and vertical speed-based launch.")
            .defineInRange("railJumpMomentum", 0.5, 0.1, 1.0);

    public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();

    private Config() {}
}
