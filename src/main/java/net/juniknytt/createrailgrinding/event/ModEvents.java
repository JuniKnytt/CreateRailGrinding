package net.juniknytt.createrailgrinding.event;

import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.effect.ModEffects;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;


@EventBusSubscriber(modid = RailGrind.MODID)
public class ModEvents
{
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;
        // Decrement the post-grind cooldown counter every server tick. Has to run for
        // grinding *and* non-grinding players — once stop() seeds the counter, only
        // tickCooldown can drain it back to zero so a new grind can start. Cheap when
        // the player has no cooldown entry (single map lookup, early return).
        RailGrindHandler.tickCooldown(player);
        // Drain the post-portal-transit cooldown every server tick. Has to run for every
        // player (grinder or not) so the timer doesn't get stuck after the player drops out
        // of grind mode mid-cooldown.
        RailGrindHandler.tickPortalTransitCooldown(player);
        // Sustained-overlap kick check. Has to run every tick for every player (grinder or
        // not) because the resulting "crushed" gate also blocks new grind starts — a player
        // standing inside a parked carriage shouldn't be allowed to initiate from there, and
        // the only way the gate flips is via this tick.
        RailGrindHandler.tickTrainOverlap(player);
        RailGrindHandler.tick(player);
    }

    /**
     * Cancel fall damage during the post-dismount immunity window. Without this, stepping off
     * an elevated rail or landing from a stopWithLaunch arc lands hard enough to kill at
     * TOP_SPEED. Runs at default priority so the cancellation lands before
     * {@link #onIncomingDamageStopGrind} (LOWEST), which keeps the kick-out from re-firing on
     * fall damage absorbed during the window.
     */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!event.getSource().is(DamageTypes.FALL)) return;
        if (RailGrindHandler.hasFallImmunity(player)) {
            event.setCanceled(true);
        }
    }

    /**
     * Drop the grind whenever a grinding player takes any incoming damage. The kick is the
     * only other damage-related behavior in the mod — no amplification, no custom damage
     * source. LOWEST priority + the isCanceled check let other mods veto damage (totems,
     * shields, etc.) — and the fall-immunity cancel above — skip the dismount on a hit that
     * would land as 0. Server-side only because {@link RailGrindHandler}'s active map is
     * server-authoritative; the client follows via the pose-sync packet stop() emits.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onIncomingDamageStopGrind(LivingIncomingDamageEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        if (!RailGrindHandler.isGrinding(player)) return;
        RailGrindHandler.stop(player);
    }

    /**
     * Wipe leftover grind state on login/logout/respawn. Without this, a player
     * who quits or dies mid-grind rejoins with {@code noGravity = true} (which
     * is persisted to the player's NBT via SynchedEntityData) and floats forever.
     */
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        clearGrindState(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        clearGrindState(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        clearGrindState(event.getEntity());
    }

    /**
     * Force a rail-grind restart when a player who was grinding crosses any dimension boundary.
     * Covers the cases the instant-portal mixin enables: vanilla portal flow fires inside
     * {@code Entity#tick()} (before PlayerTickEvent.Post runs), the player lands in the new
     * dimension, and this listener picks up the resume. The old GrindState references a
     * TrackGraph in the old dimension and is dropped inside
     * {@link RailGrindHandler#handleDimensionChange} — same path also handles command teleports
     * and any other ChangedDimension fire while grinding.
     */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!RailGrindHandler.isGrinding(sp)) return;
        RailGrindHandler.handleDimensionChange(sp);
    }

    private static void clearGrindState(Player player) {
        // Removes from ACTIVE map and broadcasts the false sync packet
        RailGrindHandler.stop(player);
        // Defensive: even if stop() short-circuited (player wasn't in ACTIVE),
        // explicitly clear the persisted flags so the player can't be left floating.
        player.setNoGravity(false);
        player.noPhysics = false;
        player.fallDistance = 0.0F;
    }

    private static final ResourceLocation BAR_OF_CHOCOLATE = ResourceLocation.fromNamespaceAndPath("create", "bar_of_chocolate");

    /**
     * Eating a Create bar of chocolate grants Sonic Wind for 1 minute. Hidden particles
     * (visible=false) so the player doesn't trail effect motes; the icon still shows in the HUD.
     */
    @SubscribeEvent
    public static void onItemUseFinish(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        ItemStack stack = event.getItem();
        if (stack.isEmpty()) return;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!BAR_OF_CHOCOLATE.equals(id)) return;
        player.addEffect(new MobEffectInstance(
                ModEffects.SONIC_WIND,
                ModEffects.SONIC_WIND_DURATION_TICKS,
                0,
                false,
                false,
                true));
    }
}
