package net.juniknytt.createrailgrinding.client;

/**
 * Render-thread flag set while {@link net.minecraft.client.gui.screens.inventory.InventoryScreen}
 * is rendering an entity portrait (survival / creative inventory, beacon, smithing, recipe-book
 * results, and any mod that funnels through {@code renderEntityInInventory}).
 *
 * <p><b>Why this exists</b> — {@link net.juniknytt.createrailgrinding.mixin.client.PlayerModelMixin}
 * has a first-person-hand skip predicated on {@code entity == mc.player && isFirstPerson()}, which
 * also matches the inventory portrait render whenever the world camera happens to be in first-
 * person (the default). To keep the rail-grind pose visible on the inventory model — including
 * pivoting/rotating/leaning/sneak/jump — the mixin additionally excludes
 * {@link #isRendering()} from the hand-render skip. The flag therefore disambiguates the
 * two "first-person + local player" code paths: hand render → suppress, inventory render →
 * apply pose.
 *
 * <p>Nesting-safe via a depth counter — some screens render an entity inside another entity's
 * preview slot. Vanilla {@code @Inject(RETURN)} doesn't catch thrown exceptions, so a render
 * crash could leave the depth pinned above zero; in practice the inventory render path doesn't
 * throw, and the worst-case visual symptom would be the grind pose persisting on the next
 * first-person hand render for one frame.
 */
public final class InventoryRenderTracker {
    private static int depth;

    private InventoryRenderTracker() {}

    public static void push() { depth++; }
    public static void pop() { if (depth > 0) depth--; }
    public static boolean isRendering() { return depth > 0; }
}
