package net.juniknytt.createrailgrinding.sound;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
        DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, RailGrind.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> GRIND_COLLIDE   = register("grind_collide");

    public static final DeferredHolder<SoundEvent, SoundEvent> GRIND_FAST_LOOP = register("grind_fast_loop");

    public static final DeferredHolder<SoundEvent, SoundEvent> GRIND_SLOW_LOOP = register("grind_slow_loop");

    private ModSounds() {}

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUNDS.register(name, () ->
            SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, name)));
    }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }
}
