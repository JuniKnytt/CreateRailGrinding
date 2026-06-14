package net.juniknytt.createrailgrinding.advancement;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModTriggers {
    public static final DeferredRegister<CriterionTrigger<?>> TRIGGERS =
            DeferredRegister.create(Registries.TRIGGER_TYPE, RailGrind.MODID);

    public static final DeferredHolder<CriterionTrigger<?>, RailGrindTrigger> RAIL_GRIND =
            TRIGGERS.register("rail_grind", RailGrindTrigger::new);

    public static final DeferredHolder<CriterionTrigger<?>, CustomBootsTrigger> OBTAIN_CUSTOM_BOOTS =
            TRIGGERS.register("obtain_custom_boots", CustomBootsTrigger::new);

    public static final DeferredHolder<CriterionTrigger<?>, FallNegateTrigger> FALLDAMAGE_NEGATE =
            TRIGGERS.register("falldamage_negate", FallNegateTrigger::new);

    public static final DeferredHolder<CriterionTrigger<?>, RailGrindEffectTrigger> RAIL_GRIND_EFFECT =
            TRIGGERS.register("railgrind_effect", RailGrindEffectTrigger::new);

    private ModTriggers() {}

    public static void register(IEventBus modBus) {
        TRIGGERS.register(modBus);
    }
}
