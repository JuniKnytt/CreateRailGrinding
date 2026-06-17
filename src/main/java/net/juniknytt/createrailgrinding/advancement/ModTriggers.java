package net.juniknytt.createrailgrinding.advancement;

import net.minecraft.advancements.CriteriaTriggers;

public final class ModTriggers {
    public static RailGrindTrigger RAIL_GRIND;

    public static CustomBootsTrigger OBTAIN_CUSTOM_BOOTS;

    public static FallNegateTrigger FALLDAMAGE_NEGATE;

    public static RailGrindEffectTrigger RAIL_GRIND_EFFECT;

    private ModTriggers() {}

    public static void register() {
        RAIL_GRIND = CriteriaTriggers.register(new RailGrindTrigger());
        OBTAIN_CUSTOM_BOOTS = CriteriaTriggers.register(new CustomBootsTrigger());
        FALLDAMAGE_NEGATE = CriteriaTriggers.register(new FallNegateTrigger());
        RAIL_GRIND_EFFECT = CriteriaTriggers.register(new RailGrindEffectTrigger());
    }
}
