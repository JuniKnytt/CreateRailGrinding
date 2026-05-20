package net.juniknytt.createrailgrinding.enchantment;

import com.simibubi.create.content.equipment.armor.DivingBootsItem;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public final class ModEnchantments {
    public static final ResourceKey<Enchantment> RAILGRIND_ENCHANTMENT = ResourceKey.create(
            Registries.ENCHANTMENT,
            ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "railgrind_enchantment"));

    private static final ResourceLocation RAILGRIND_ENCHANTMENT_ID = RAILGRIND_ENCHANTMENT.location();

    private ModEnchantments() {}

    public static boolean hasRailgrindEnchantment(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (containsRailgrind(stack.getEnchantments())) return true;
        ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        return stored != null && containsRailgrind(stored);
    }

    private static boolean containsRailgrind(ItemEnchantments enchantments) {
        for (Holder<Enchantment> h : enchantments.keySet()) {
            if (h.unwrapKey().map(ResourceKey::location).filter(RAILGRIND_ENCHANTMENT_ID::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isRailGrindBoots(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() instanceof DivingBootsItem) return true;
        return hasRailgrindEnchantment(stack);
    }

    public static boolean isWearingRailGrindBoots(Player player) {
        return isRailGrindBoots(player.getItemBySlot(EquipmentSlot.FEET));
    }
}
