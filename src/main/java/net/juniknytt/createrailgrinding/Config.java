package net.juniknytt.createrailgrinding;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {

    private static final ForgeConfigSpec.Builder CLIENT_BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue DEBUG_MODE = CLIENT_BUILDER
            .comment("DO NOT ENABLE UNLESS DEBUG TESTING. Used for testing. Displays many values while Rail Grinding.")
            .define("debugMode", false);

    public static final ForgeConfigSpec.DoubleValue SOUND_VOLUME = CLIENT_BUILDER
            .comment("Volume for all Rail Grinding sounds.")
            .defineInRange("soundVolume", 0.5, 0.0, 1.0);

    public static final ForgeConfigSpec.BooleanValue CAMERA_ROLL_ON_LEAN = CLIENT_BUILDER
            .comment("Camera Roll when Rail Grinding. May cause motion sickness.")
            .define("cameraRollOnLean", false);

    public static final ForgeConfigSpec SPEC = CLIENT_BUILDER.build();

    private static final ForgeConfigSpec.Builder SERVER_BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.DoubleValue TOP_GRIND_SPEED = SERVER_BUILDER
            .comment("Top speed while accelerating with crouch.")
            .defineInRange("topGrindSpeed", 0.75, 0.1, 1.0);

    public static final ForgeConfigSpec.DoubleValue CRUISE_GRIND_SPEED = SERVER_BUILDER
            .comment("Cruising speed without accelerating with crouch.")
            .defineInRange("cruiseGrindSpeed", 0.75, 0.1, 2.0);

    public static final ForgeConfigSpec.DoubleValue CROUCH_ACCELERATION = SERVER_BUILDER
            .comment("Acceleration while crouching. Multiplier applied to base acceleration when shift is held.")
            .defineInRange("crouchAcceleration", 1.0, 0.1, 2.0);

    public static final ForgeConfigSpec.DoubleValue DOWNWARD_MOMENTUM_GAIN = SERVER_BUILDER
            .comment("Acceleration multiplier going down inclines.")
            .defineInRange("downwardMomentumGain", 0.5, 0.1, 2.0);

    public static final ForgeConfigSpec.DoubleValue RAIL_JUMP_CHARGE = SERVER_BUILDER
            .comment("How far you'll leap based on how long you charge your jump. Scales both horizontal and vertical charge bonus.")
            .defineInRange("railJumpCharge", 0.5, 0.1, 1.0);

    public static final ForgeConfigSpec.DoubleValue RAIL_JUMP_MOMENTUM = SERVER_BUILDER
            .comment("How far you'll leap based speed. Scales both horizontal and vertical speed-based launch.")
            .defineInRange("railJumpMomentum", 0.5, 0.1, 1.0);

    public static final ForgeConfigSpec.BooleanValue AUTO_DEPLOY_ELYTRA = SERVER_BUILDER
            .comment("Automatically engage Elytra flight when jumping off a Rail Grind with any Elytra equipped.")
            .define("autoDeployElytra", true);

    public static final ForgeConfigSpec.BooleanValue SYNC_DEBUG_TO_CLIENTS = SERVER_BUILDER
            .comment("DO NOT ENABLE UNLESS DEBUG TESTING. Broadcasts Rail Grind debug values for players using debug mode.")
            .define("syncDebugToClients", false);

    public static final ForgeConfigSpec SERVER_SPEC = SERVER_BUILDER.build();

    private Config() {}
}
