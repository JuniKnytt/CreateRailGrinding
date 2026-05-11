package net.juniknytt.createrailgrinding.cosmetic;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Set;

/**
 * Cosmetic skin gate for rail-grind boots renamed in an anvil. A stack qualifies when
 * it (a) is in the {@code createrailgrinding:is_diving_boots} item tag — Create's two
 * diving boots are added by our tag file, and addons can extend it via datapack — and
 * (b) carries a custom hover name matching one of {@link #NAMES} (case-insensitive,
 * trimmed). Both checks live in {@link #matches(ItemStack)}.
 *
 * Append new aliases to {@link #NAMES}. Comparison lower-cases via {@link Locale#ROOT}
 * to keep the match locale-independent.
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
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.is(IS_DIVING_BOOTS)) return false;
        if (!stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) return false;
        String name = stack.getHoverName().getString().trim().toLowerCase(Locale.ROOT);
        return NAMES.contains(name);
    }
}
