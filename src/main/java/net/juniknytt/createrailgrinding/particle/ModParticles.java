package net.juniknytt.createrailgrinding.particle;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
        DeferredRegister.create(BuiltInRegistries.PARTICLE_TYPE, RailGrind.MODID);

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SPARK =
        PARTICLE_TYPES.register("spark", () -> new SimpleParticleType(false));

    private ModParticles() {}

    public static void register(IEventBus modBus) {
        PARTICLE_TYPES.register(modBus);
    }
}
