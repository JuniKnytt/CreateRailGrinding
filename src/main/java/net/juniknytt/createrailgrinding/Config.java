package net.juniknytt.createrailgrinding;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {

    //TO DO SORT THESE OUT, THEY ARE OKAY AS THEY ARE

    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue DEBUG_MODE = CLIENT_BUILDER
            .comment("DO NOT ENABLE UNLESS DEBUG TESTING. Used for testing. Displays many values while rail-grinding.")
            .define("debugMode", false);

    public static final ModConfigSpec.DoubleValue SOUND_VOLUME = CLIENT_BUILDER
            .comment("Volume for all rail-grinding sounds.")
            .defineInRange("soundVolume", 0.5, 0.0, 1.0);

    public static final ModConfigSpec.BooleanValue OVERRIDE_KEYBINDINGS = CLIENT_BUILDER
            .comment("Overrides rail-grinding actions with alternative keybindings under Key Binds within Controls in Settings.")
            .define("overrideKeybindings", false);

    public static final ModConfigSpec.BooleanValue CAMERA_ROLL_ON_LEAN = CLIENT_BUILDER
            .comment("Camera Roll when rail-grinding. May cause motion sickness.")
            .define("cameraRollOnLean", false);

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
            .comment("Acceleration multiplier going down inclines.")
            .defineInRange("downwardMomentumGain", 0.5, 0.1, 2.0);

    public static final ModConfigSpec.DoubleValue RAIL_JUMP_CHARGE = SERVER_BUILDER
            .comment("How far you'll leap based on how long you charge your jump. Scales both horizontal and vertical charge bonus.")
            .defineInRange("railJumpCharge", 0.5, 0.1, 1.0);

    public static final ModConfigSpec.DoubleValue RAIL_JUMP_MOMENTUM = SERVER_BUILDER
            .comment("How far you'll leap based speed. Scales both horizontal and vertical speed-based launch.")
            .defineInRange("railJumpMomentum", 0.5, 0.1, 1.0);

    public static final ModConfigSpec.BooleanValue AUTO_DEPLOY_ELYTRA = SERVER_BUILDER
            .comment("Automatically engage Elytra flight when jumping off a rail-grind with any Elytra equipped.")
            .define("autoDeployElytra", true);

    public static final ModConfigSpec.BooleanValue SYNC_DEBUG_TO_CLIENTS = SERVER_BUILDER
            .comment("DO NOT ENABLE UNLESS DEBUG TESTING. Broadcasts rail-grind debug values for players using debug mode.")
            .define("syncDebugToClients", false);

    public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();

    private Config() {}
}
