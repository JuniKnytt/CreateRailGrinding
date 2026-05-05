package net.juniknytt.createrailgrinding.client;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.content.equipment.armor.DivingBootsItem;
import com.simibubi.create.foundation.item.TooltipHelper;
import net.createmod.catnip.lang.FontHelper;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Inserts two condition+behaviour paragraphs ("When Near Rails" and "While Rail Grinding")
 * into every Create diving-boots tooltip — copper and netherite both — between Create's
 * summary paragraph ("a pair of _heavy boots_…") and the existing "When Worn" / behaviour1
 * paragraph, but only when the player is holding shift so the new lines live inside Create's
 * "Hold [Shift] for Summary" block.
 *
 * Insertion point is just AFTER the first empty Component following the item name — that's
 * the blank Create emits between the summary and the first condition header. By inserting
 * after it (with a blank between our two paragraphs and a trailing blank before Create's
 * own condition), we end up with:
 *
 *   summary
 *   <blank>              ← Create's existing summary↔condition separator
 *   When Near Rails      ← our header (gray/white, like Create's "When Worn")
 *   Grants the ability … ← our behaviour
 *   <blank>              ← separator between our two paragraphs
 *   While Rail Grinding  ← our second header
 *   Hold Sneak …         ← our second behaviour
 *   <blank>              ← our trailing separator
 *   When Worn            ← Create's existing condition
 *   Wielder descends …   ← Create's existing behaviour1
 *
 * Formatting goes through TooltipHelper.cutStringTextComponent — the same path Create's own
 * item descriptions take — so _underscored_ words render as highlights matching the
 * `_heavy_ _boots_` convention of the existing diving-boots lang strings (see
 * `assets/create/lang/default/tooltips.json`). Behaviour lines use the 4-arg overload with
 * indent 1, matching `ItemDescription$Builder.build`'s own indent for behaviour text.
 */
@EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public final class DivingBootsTooltip {
    private DivingBootsTooltip() {}

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (!(event.getItemStack().getItem() instanceof DivingBootsItem)) return;
        if (!Screen.hasShiftDown()) return;

        List<Component> tooltip = event.getToolTip();
        List<Component> header = TooltipHelper.cutStringTextComponent(
                "When Near Rails",
                FontHelper.Palette.GRAY_AND_WHITE);
        List<Component> description = TooltipHelper.cutStringTextComponent(
                "Grants the ability to _grind_ on the rails of _Train Tracks._ _Interact (R-Click)_ with _Train Tracks_ on an empty hand, or hold _Sneak_ and _Jump_ while near _Train Tracks._",
                FontHelper.Palette.STANDARD_CREATE.primary(),
                FontHelper.Palette.STANDARD_CREATE.highlight(),
                1);
        List<Component> header2 = TooltipHelper.cutStringTextComponent(
                "While Rail Grinding",
                FontHelper.Palette.GRAY_AND_WHITE);
        List<Component> description2 = TooltipHelper.cutStringTextComponent(
                "Hold _Sneak_ to accelerate. Press _Jump_ to trick off the rail. _Interact (R-Click)_ the rail to stop.",
                FontHelper.Palette.STANDARD_CREATE.primary(),
                FontHelper.Palette.STANDARD_CREATE.highlight(),
                1);

        List<Component> block = new ArrayList<>(
                header.size() + description.size() + header2.size() + description2.size() + 2);
        block.addAll(header);
        block.addAll(description);
        block.add(Component.empty());
        block.addAll(header2);
        block.addAll(description2);
        block.add(Component.empty());

        int firstBlank = -1;
        for (int i = 1; i < tooltip.size(); i++) {
            if (tooltip.get(i).getString().isEmpty()) {
                firstBlank = i;
                break;
            }
        }

        if (firstBlank < 0) {
            tooltip.addAll(block);
        } else {
            tooltip.addAll(firstBlank + 1, block);
        }
    }
}
