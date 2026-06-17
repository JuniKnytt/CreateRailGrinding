package net.juniknytt.createrailgrinding.particle;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
        DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, RailGrind.MODID);

    public static final RegistryObject<SimpleParticleType> SPARK =
        PARTICLE_TYPES.register("spark", () -> new SimpleParticleType(false));

    private ModParticles() {}

    public static void register(IEventBus modBus) {
        PARTICLE_TYPES.register(modBus);
    }
}
