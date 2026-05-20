package net.juniknytt.createrailgrinding.client;

import java.util.List;

import com.simibubi.create.foundation.item.TooltipHelper;
import net.createmod.catnip.lang.FontHelper;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

@EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public final class BarOfChocolateTooltip {
    private static final ResourceLocation BAR_OF_CHOCOLATE =
            ResourceLocation.fromNamespaceAndPath("create", "bar_of_chocolate");
    private static final String SUMMARY_KEY = "effect.createrailgrinding.railgrindboost.description";
    private static final String CONDITION_KEY = "effect.createrailgrinding.railgrindboost.condition";
    private static final String BEHAVIOUR_KEY = "effect.createrailgrinding.railgrindboost.behaviour";
    private static final String HOLD_FOR_DESCRIPTION_KEY = "create.tooltip.holdForDescription";
    private static final String KEY_SHIFT_KEY = "create.tooltip.keyShift";

    private BarOfChocolateTooltip() {}

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem());
        if (!BAR_OF_CHOCOLATE.equals(id)) return;
        List<Component> tooltip = event.getToolTip();

        if (Screen.hasShiftDown()) {

            tooltip.addAll(TooltipHelper.cutStringTextComponent(
                    Component.translatable(SUMMARY_KEY).getString(),
                    FontHelper.Palette.STANDARD_CREATE));
            tooltip.add(Component.empty());
            tooltip.addAll(TooltipHelper.cutStringTextComponent(
                    Component.translatable(CONDITION_KEY).getString(),
                    FontHelper.Palette.GRAY_AND_WHITE));
            tooltip.addAll(TooltipHelper.cutStringTextComponent(
                    Component.translatable(BEHAVIOUR_KEY).getString(),
                    FontHelper.Palette.STANDARD_CREATE.primary(),
                    FontHelper.Palette.STANDARD_CREATE.highlight(),
                    1));
        } else {
            Component shiftKey = Component.translatable(KEY_SHIFT_KEY).withStyle(ChatFormatting.GRAY);
            tooltip.add(Component.translatable(HOLD_FOR_DESCRIPTION_KEY, shiftKey)
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
