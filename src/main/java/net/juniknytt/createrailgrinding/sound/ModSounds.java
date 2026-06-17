package net.juniknytt.createrailgrinding.sound;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
        DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, RailGrind.MODID);

    public static final RegistryObject<SoundEvent> GRIND_COLLIDE   = register("grind_collide");

    public static final RegistryObject<SoundEvent> GRIND_FAST_LOOP = register("grind_fast_loop");

    public static final RegistryObject<SoundEvent> GRIND_SLOW_LOOP = register("grind_slow_loop");

    private ModSounds() {}

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUNDS.register(name, () ->
            SoundEvent.createVariableRangeEvent(new ResourceLocation(RailGrind.MODID, name)));
    }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }
}
