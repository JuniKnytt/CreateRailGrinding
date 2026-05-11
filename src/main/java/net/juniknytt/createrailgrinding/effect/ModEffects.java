package net.juniknytt.createrailgrinding.effect;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEffects {
    public static final DeferredRegister<MobEffect> EFFECTS =
        DeferredRegister.create(BuiltInRegistries.MOB_EFFECT, RailGrind.MODID);

    // Bumps top sneak speed, sneak acceleration, cruise grind speed, and rail jump charge by this factor while active.
    public static final double SONIC_WIND_MULTIPLIER = 1.25;
    // 1 minute, in ticks.
    public static final int SONIC_WIND_DURATION_TICKS = 1200;

    // Display name "Sonic Wind"; registry path is the internal name.
    public static final DeferredHolder<MobEffect, MobEffect> SONIC_WIND =
        EFFECTS.register("railgrindboost", SonicWindEffect::new);

    private ModEffects() {}

    public static double sonicWindMultiplier(Player player) {
        return player.hasEffect(SONIC_WIND) ? SONIC_WIND_MULTIPLIER : 1.0;
    }

    public static void register(IEventBus modBus) {
        EFFECTS.register(modBus);
    }
}
