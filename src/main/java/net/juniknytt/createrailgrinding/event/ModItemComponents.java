package net.juniknytt.createrailgrinding.event;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ModifyDefaultComponentsEvent;

// Mod-bus event hooks that patch other mods' item components at registration. Lives in
// event/ alongside ModEvents but uses Bus.MOD because ModifyDefaultComponentsEvent is a
// mod-bus event.
@EventBusSubscriber(modid = RailGrind.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModItemComponents {
    private static final ResourceLocation BAR_OF_CHOCOLATE =
            ResourceLocation.fromNamespaceAndPath("create", "bar_of_chocolate");

    private ModItemComponents() {}

    /**
     * Flip {@code canAlwaysEat=true} on Create's bar of chocolate so it can be eaten at full
     * hunger. Rebuilds the {@link FoodProperties} record with existing nutrition/saturation/
     * eatSeconds/effects preserved — only the flag changes.
     */
    @SubscribeEvent
    public static void onModifyDefaultComponents(ModifyDefaultComponentsEvent event) {
        Item bar = BuiltInRegistries.ITEM.get(BAR_OF_CHOCOLATE);
        if (bar == Items.AIR) return;
        FoodProperties current = bar.components().get(DataComponents.FOOD);
        if (current == null || current.canAlwaysEat()) return;
        FoodProperties patched = new FoodProperties(
                current.nutrition(),
                current.saturation(),
                true,
                current.eatSeconds(),
                current.usingConvertsTo(),
                current.effects());
        event.modify(bar, builder -> builder.set(DataComponents.FOOD, patched));
    }
}
