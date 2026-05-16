package net.juniknytt.createrailgrinding.enchantment;

import com.simibubi.create.content.equipment.armor.DivingBootsItem;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

public final class ModEnchantments {
    public static final ResourceKey<Enchantment> RAIL_GRINDER = ResourceKey.create(
            Registries.ENCHANTMENT,
            ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "rail_grinder"));

    private ModEnchantments() {}

    public static boolean hasRailGrinder(ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (Holder<Enchantment> h : stack.getEnchantments().keySet()) {
            if (h.is(RAIL_GRINDER)) return true;
        }
        return false;
    }

    public static boolean isRailGrindBoots(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() instanceof DivingBootsItem) return true;
        return hasRailGrinder(stack);
    }

    public static boolean isWearingRailGrindBoots(Player player) {
        return isRailGrindBoots(player.getItemBySlot(EquipmentSlot.FEET));
    }
}
