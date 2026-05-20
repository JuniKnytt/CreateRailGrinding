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

    public static final double SONIC_WIND_MULTIPLIER = 1.25;

    public static final int SONIC_WIND_DURATION_TICKS = 1200;

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
