package net.juniknytt.createrailgrinding.cosmetic;

import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.enchantment.ModEnchantments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Set;

/**
 * Cosmetic skin gate for rail-grind boots renamed in an anvil. The skin applies through
 * either of two independent routines — {@link #matches(ItemStack)} is the OR of the two
 * so callers can stay agnostic, but the routines themselves are kept separate so each
 * can evolve on its own.
 *
 *   Routine A — {@link #matchesDivingBoots(ItemStack)}:
 *     Stack is in tag {@code #createrailgrinding:is_diving_boots} (Create's two diving
 *     boots by default; addons can extend via datapack) AND has a matching custom name.
 *     This is the original cosmetic path — diving boots get the skin from a rename alone.
 *
 *   Routine B — {@link #matchesRailgrindEnchanted(ItemStack)}:
 *     Stack carries the rail-grind enchantment ({@code createrailgrinding:railgrind_enchantment})
 *     AND has a matching custom name. Lets ANY foot armor — iron, leather, modded — opt
 *     into the skin once the player has applied Rail Rider via the enchanting table or anvil.
 *
 * Custom-name check is shared by {@link #hasMatchingName(ItemStack)}. Comparison
 * lower-cases via {@link Locale#ROOT} so the match is locale-independent. Append new
 * aliases to {@link #NAMES}.
 */
public final class CustomBootSkin {
    private CustomBootSkin() {}

    public static final TagKey<Item> IS_DIVING_BOOTS = TagKey.create(
            net.minecraft.core.registries.Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "is_diving_boots"));

    /** Add new aliases here. Whitespace is trimmed and case is ignored at match time. */
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

            "gotta go fast",
            "got to go fast"


    );

    public static boolean matches(ItemStack stack) {
        return matchesDivingBoots(stack) || matchesRailgrindEnchanted(stack);
    }

    /** Routine A: diving boots get the skin from a rename alone. */
    public static boolean matchesDivingBoots(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.is(IS_DIVING_BOOTS)) return false;
        return hasMatchingName(stack);
    }

    /** Routine B: any boots get the skin from a rename once enchanted with Rail Rider. */
    public static boolean matchesRailgrindEnchanted(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!hasMatchingName(stack)) return false;
        return ModEnchantments.hasRailgrindEnchantment(stack);
    }

    private static boolean hasMatchingName(ItemStack stack) {
        if (!stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) return false;
        String name = stack.getHoverName().getString().trim().toLowerCase(Locale.ROOT);
        return NAMES.contains(name);
    }
}
