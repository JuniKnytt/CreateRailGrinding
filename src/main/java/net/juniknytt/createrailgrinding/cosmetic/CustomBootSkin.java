package net.juniknytt.createrailgrinding.cosmetic;

import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.enchantment.ModEnchantments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Set;

public final class CustomBootSkin {
    private CustomBootSkin() {}

    public static final TagKey<Item> IS_DIVING_BOOTS = TagKey.create(
            net.minecraft.core.registries.Registries.ITEM,
            new ResourceLocation(RailGrind.MODID, "is_diving_boots"));

    public static final Set<String> NAMES = Set.of(
            "sonic",
            "sonic shoes",
            "sonic boots",
            "sonic grind shoes",

            "soap",
            "soap shoes",
            "soap boots",

            "2g hi-speed shoes",
            "2g hi speed shoes",
            "2g high speed shoes",

            "2g hi-speed boots",
            "2g hi speed boots",
            "2g high speed boots",

            "sonic adventure 2 shoes",
            "sonic adventure 2 battle shoes",
            "sonic adventure 2 boots",
            "sonic adventure 2 battle boots",

            "rollin at the speed of sound",
            "rollin around at the speed of sound",
            "rolling around at the speed of sound",

            "grind shoes",
            "grind boots",
            "grinding shoes",
            "grinding boots",
            "railgrind shoes",
            "railgrind boots",
            "railgrinding shoes",
            "railgrinding boots",
            "rail grind shoes",
            "rail grind boots",
            "rail grinding shoes",
            "rail grinding boots",
            "rail-grind shoes",
            "rail-grind boots",
            "rail-grinding shoes",
            "rail-grinding boots",

            "faker",
            "faker boots",
            "faker shoes",
            "blue wind",
            "blue blur",
            "sonic the hedgehog",
            "sonic the hedgehog boots",
            "sonic the hedgehog shoes",

            "gotta go fast",
            "got to go fast"

    );

    public static boolean matches(ItemStack stack) {
        return matchesDivingBoots(stack) || matchesRailgrindEnchanted(stack);
    }

    public static boolean matchesDivingBoots(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.is(IS_DIVING_BOOTS)) return false;
        return hasMatchingName(stack);
    }

    public static boolean matchesRailgrindEnchanted(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!hasMatchingName(stack)) return false;
        return ModEnchantments.hasRailgrindEnchantment(stack);
    }

    private static boolean hasMatchingName(ItemStack stack) {
        if (!stack.hasCustomHoverName()) return false;
        String name = stack.getHoverName().getString().trim().toLowerCase(Locale.ROOT);
        return NAMES.contains(name);
    }
}
