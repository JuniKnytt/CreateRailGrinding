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

/**
 * Adds a Create-style "Hold [Shift] for Summary" tooltip block to Create's bar of chocolate,
 * because the base item ships without one. The summary, condition, and behaviour paragraphs
 * only appear when Shift is held — matching how Create's own item descriptions toggle. The
 * potion-style "Sonic Wind (1:00)" line is intentionally omitted; the behaviour paragraph
 * already names the effect and its duration.
 *
 * Layout when Shift is held:
 *   Bar of Chocolate                            ← vanilla item name
 *   A tasty treat that …                        ← summary, STANDARD_CREATE palette
 *   <blank>
 *   When Eaten                                  ← condition header, gray/white
 *    Grants _Sonic_ _Wind_ for _1 minute_, …    ← behaviour, indented, STANDARD_CREATE palette
 *
 * Layout when Shift is NOT held:
 *   Bar of Chocolate
 *   Hold [Shift] for Summary                    ← create.tooltip.holdForDescription, dark gray
 *
 * Reuses Create's own lang keys (create.tooltip.holdForDescription, create.tooltip.keyShift)
 * so the prompt is localized identically to Create's items. Behaviour text uses the
 * `_underscore_` highlight syntax handled by FontHelper.cutTextComponent — same convention as
 * Create's own behaviour lang strings (see DivingBootsTooltip and assets/create/lang/…).
 */
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
            // Summary uses STANDARD_CREATE (Create's signature gold-on-orange) so _underscored_
            // phrases render with the same highlight tint Create's own summary blocks use.
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
