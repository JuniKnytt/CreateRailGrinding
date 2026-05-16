package net.juniknytt.createrailgrinding.event;

import net.juniknytt.createrailgrinding.Config;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.cosmetic.CustomBootSkin;
import net.juniknytt.createrailgrinding.effect.ModEffects;
import net.juniknytt.createrailgrinding.enchantment.ModEnchantments;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AnvilUpdateEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
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
        // Drain the deferred cross-dim re-grind queue before tick(). When a pending entry
        // is present the player has no ACTIVE GrindState yet, so tick() short-circuits
        // anyway — but draining first lets the silent grind start fire on the same tick
        // the chunk gate opens, instead of waiting one more tick for tick() to pick it up.
        RailGrindHandler.tickPendingRegrind(player);
        RailGrindHandler.tick(player);

        // QA-debug bridge: when enabled in server-config, ship the per-tick grind snapshot
        // (HUD lines + debug-box frame data + always-on overlap/cooldown counters) to the
        // player so their client-side HUD and debug renderer can show values that otherwise
        // only exist server-side. Gated by the config so production play never pays the
        // packet cost.
        if (Config.SYNC_DEBUG_TO_CLIENTS.get() && player instanceof ServerPlayer sp) {
            RailGrindHandler.broadcastDebugSnapshot(sp);
        }
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
        // Cancel ALL damage while the player is in the cross-dim re-attach grace window.
        // Server-side sp.position can briefly track the client's pre-Sync fall through lava /
        // void / suffocating geometry at the destination (typical nether portal exits) while
        // the new LocalPlayer's noGravity sync is still in flight. Without this gate the
        // resulting LivingIncomingDamageEvent feeds onIncomingDamageStopGrind (LOWEST priority,
        // checks isCanceled), which would end the grind before the player can even act —
        // they'd see the grind pose flash on the destination side, then drop. Cancelling
        // the damage itself (rather than just suppressing the kick) also prevents the player
        // taking the spurious damage from a position they didn't actually visit.
        if (RailGrindHandler.isInReattachGrace(player)) {
            event.setCanceled(true);
            return;
        }
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
        // Defensive: even if onIncomingDamage's cancel didn't fire (another mod re-issued
        // the event, ordering differed, etc.), skip the kick during the cross-dim grace.
        // The grace itself is the canonical "this player is mid-transit, don't end their
        // grind for any reason" gate.
        if (RailGrindHandler.isInReattachGrace(player)) return;
        RailGrindHandler.stop(player, RailGrindHandler.StopReason.DAMAGE);
    }

    /**
     * Drop the grind whenever a grinding player's boots slot changes. Closes the
     * armor-swap exploit where a player could trade their diving boots out mid-grind
     * (right-click swap, inventory drag, etc.) and keep grinding without them.
     * {@link LivingEquipmentChangeEvent} fires only when the slot's {@code ItemStack}
     * reference is replaced — not on in-place durability damage — so legitimate
     * grinding doesn't trip it.
     */
    @SubscribeEvent
    public static void onBootsChangeStopGrind(LivingEquipmentChangeEvent event) {
        if (event.getSlot() != EquipmentSlot.FEET) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        if (!RailGrindHandler.isGrinding(player)) return;
        RailGrindHandler.stop(player, RailGrindHandler.StopReason.BOOTS_SWAP);
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

    /**
     * Drop the grind whenever the player is teleported by anything that fires
     * {@link EntityTeleportEvent} — vanilla covers {@code /tp}, {@code /teleport},
     * {@code /spreadplayers}, ender pearls, chorus fruit, and enderman teleports; subscribing
     * to the parent event also catches mod teleports that fire any subclass. Same-dimension only:
     * portal flow doesn't fire this event (it uses {@link PlayerEvent.PlayerChangedDimensionEvent},
     * already handled above with a resume-on-the-other-side path).
     *
     * <p>Internal same-dim teleports the mod itself emits ({@code TeleportToRailPacket}'s
     * {@code player.teleportTo(x,y,z)}) bypass this event entirely — vanilla's three-arg
     * {@code teleportTo} doesn't fire {@link EntityTeleportEvent}; only the command/projectile
     * paths do — so right-click-to-grind continues to work without a self-cancel.
     */
    @SubscribeEvent
    public static void onTeleportStopGrind(EntityTeleportEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        if (!RailGrindHandler.isGrinding(player)) return;
        RailGrindHandler.stop(player, RailGrindHandler.StopReason.EXTERNAL_TELEPORT);
    }

    /**
     * Refresh the observer's view of a tracked player's grind state when entity tracking begins.
     * {@link net.juniknytt.createrailgrinding.network.RailGrindSyncPayload} is only emitted on
     * state transitions ({@code start} / {@code stop}) to currently-tracking observers, so a
     * player who starts tracking another player mid-grind never receives the original sync.
     * Two desync symptoms surface without this handler:
     *   <ul>
     *     <li>Chunk load: observer walks into a grinding player's range — observer sees them in
     *         vanilla pose with no sound until the grinder's next state change.</li>
     *     <li>Cross-dim arrival: observer enters a dimension where another player is already
     *         grinding — same vanilla-pose-no-sound symptom.</li>
     *   </ul>
     * Inverse case (phantom grind pose + sounds): observer had a stale {@code grinding=true}
     * entry in their {@link net.juniknytt.createrailgrinding.client.BalancingPoseTracker} from
     * a prior tracking session — the target stopped grinding while outside the observer's
     * range, so the {@code grinding=false} sync never reached them. The seeded payload — always
     * sent regardless of current grinding state — overwrites the stale entry on re-tracking.
     */
    @SubscribeEvent
    public static void onStartTrackingPlayer(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer observer)) return;
        if (!(event.getTarget() instanceof ServerPlayer target)) return;
        RailGrindHandler.syncStateToObserver(observer, target);
    }

    private static void clearGrindState(Player player) {
        // Removes from ACTIVE map and broadcasts the false sync packet
        RailGrindHandler.stop(player, RailGrindHandler.StopReason.SESSION_BOUNDARY);
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
    /**
     * Refuse anvil operations that would add the Rail Grinder enchantment onto an item in the
     * {@code createrailgrinding:is_diving_boots} tag. Diving boots already grant rail-grinding
     * natively, so stacking the enchantment on top would be redundant — and the user has asked
     * us to block the combination outright. Triggers when the LEFT input is tagged diving boots
     * and the would-be output gained Rail Grinder relative to the LEFT input (so legitimate
     * repair of an already-enchanted creative-spawned pair still works).
     */
    @SubscribeEvent
    public static void onAnvilUpdateBlockRailGrinderOnDivingBoots(AnvilUpdateEvent event) {
        ItemStack left = event.getLeft();
        if (left.isEmpty() || !left.is(CustomBootSkin.IS_DIVING_BOOTS)) return;
        ItemStack output = event.getOutput();
        if (output.isEmpty()) return;
        if (ModEnchantments.hasRailGrinder(output) && !ModEnchantments.hasRailGrinder(left)) {
            event.setOutput(ItemStack.EMPTY);
        }
    }

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
