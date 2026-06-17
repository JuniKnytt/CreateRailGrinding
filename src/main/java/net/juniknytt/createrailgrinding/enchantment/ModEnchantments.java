package net.juniknytt.createrailgrinding.enchantment;

import com.simibubi.create.content.equipment.armor.DivingBootsItem;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEnchantments {
    public static final DeferredRegister<Enchantment> ENCHANTMENTS =
            DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, RailGrind.MODID);

    public static final RegistryObject<Enchantment> RAILGRIND_ENCHANTMENT =
            ENCHANTMENTS.register("railgrind_enchantment", RailGrindEnchantment::new);

    private ModEnchantments() {}

    public static boolean hasRailgrindEnchantment(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return EnchantmentHelper.getItemEnchantmentLevel(RAILGRIND_ENCHANTMENT.get(), stack) > 0;
    }

    public static boolean isRailGrindBoots(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() instanceof DivingBootsItem) return true;
        return hasRailgrindEnchantment(stack);
    }

    public static boolean isWearingRailGrindBoots(Player player) {
        return isRailGrindBoots(player.getItemBySlot(EquipmentSlot.FEET));
    }

    public static void register(IEventBus modBus) {
        ENCHANTMENTS.register(modBus);
    }
}
