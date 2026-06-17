package net.juniknytt.createrailgrinding.client;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.content.equipment.armor.DivingBootsItem;
import com.simibubi.create.foundation.item.TooltipHelper;
import net.createmod.catnip.lang.FontHelper;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public final class DivingBootsTooltip {
    private DivingBootsTooltip() {}

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (!(event.getItemStack().getItem() instanceof DivingBootsItem)) return;
        if (!Screen.hasShiftDown()) return;

        String catchKey = ModKeyMappings.CATCH.getTranslatedKeyMessage().getString();
        String crouchKey = ModKeyMappings.GRIND_CROUCH.getTranslatedKeyMessage().getString();
        String jumpKey = ModKeyMappings.GRIND_JUMP.getTranslatedKeyMessage().getString();

        List<Component> tooltip = event.getToolTip();
        List<Component> header = TooltipHelper.cutStringTextComponent(
                I18n.get("item.createrailgrinding.diving_boots.tooltip.condition1"),
                FontHelper.Palette.GRAY_AND_WHITE);
        List<Component> description = TooltipHelper.cutStringTextComponent(
                I18n.get("item.createrailgrinding.diving_boots.tooltip.behaviour1", catchKey),
                FontHelper.Palette.STANDARD_CREATE.primary(),
                FontHelper.Palette.STANDARD_CREATE.highlight(),
                1);
        List<Component> header2 = TooltipHelper.cutStringTextComponent(
                I18n.get("item.createrailgrinding.diving_boots.tooltip.condition2"),
                FontHelper.Palette.GRAY_AND_WHITE);
        List<Component> description2 = TooltipHelper.cutStringTextComponent(
                I18n.get("item.createrailgrinding.diving_boots.tooltip.behaviour2", crouchKey, jumpKey),
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
