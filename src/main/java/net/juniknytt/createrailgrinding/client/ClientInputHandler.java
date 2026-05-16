package net.juniknytt.createrailgrinding.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorRidingHandler;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackBlockOutline;
import com.simibubi.create.content.trains.track.TrackBlockOutline.BezierPointSelection;
import net.juniknytt.createrailgrinding.Config;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.compat.Mods;
import net.juniknytt.createrailgrinding.enchantment.ModEnchantments;
import net.juniknytt.createrailgrinding.compat.SableSubLevels;
import net.juniknytt.createrailgrinding.network.ChainMountedPayload;
import net.juniknytt.createrailgrinding.network.CrossDimGraceReleasePayload;
import net.juniknytt.createrailgrinding.network.GrindAccelInputPayload;
import net.juniknytt.createrailgrinding.network.Networking;
import net.juniknytt.createrailgrinding.network.StartGrindFromNearestPayload;
import net.juniknytt.createrailgrinding.network.SteerInputPayload;
import net.juniknytt.createrailgrinding.network.StopGrindPayload;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.juniknytt.createrailgrinding.sound.GrindSoundController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public class ClientInputHandler {

    /**
     * Snapshot of the most recent empty-hand right-click for the on-screen debug overlay.
     * Updated by {@link #onClickInput} every time the local player presses use with an empty
     * main hand. Rendered by {@link RailGrindDebugHud} when {@code Config.DEBUG_MODE} is on.
     * Single-thread (client tick / render thread) so no synchronization; {@code volatile}
     * just for read-after-write visibility across the two threads that touch it.
     */
    public record LastRightClickInfo(
            long gameTimeStamp,
            String hitType,                      // "BLOCK" / "ENTITY" / "MISS" / "<none>"
            @Nullable BlockPos hitBlockPos,
            @Nullable Vec3 hitLocation,
            String parentBlockId,                // "<no block hit>" when no block
            boolean parentIsStandardTrack,
            String detectionSource               // non-empty when a rail was found, else "<none>"
    ) {}

    public static volatile @Nullable LastRightClickInfo lastRightClickInfo = null;

    /**
     * How far the sublevel sweep looks from the player's eye for sublevel-rail detection
     * on right-click. Generous — the player's reach is ~5 blocks and we want to catch any
     * sublevel that could possibly own the clicked block.
     */
    private static final double SUBLEVEL_PROBE_RADIUS = 8.0;

    /**
     * Empty-hand right-click → rail-grind start. Five-step routine:
     * <ol>
     *   <li>Player interacts with a rail outline (curve segment or {@link ITrackBlock}).</li>
     *   <li>Check rail-grind boots (Diving Boots or the Rail Grinder enchantment).</li>
     *   <li>Check the rail type (narrow / standard / wide / invalid — done server-side via
     *       {@link RailGrindHandler#isGrindableMaterial} inside the scan).</li>
     *   <li>Teleport the player to the nodegraph nearest to the interaction-vector end
     *       (server-side: same proximity scan jump+sneak uses, but rooted at the click point
     *       instead of the player's feet).</li>
     *   <li>Initiate the current variation of railgrinding.</li>
     * </ol>
     *
     * <p><b>Priority {@code HIGHEST}</b> because Steam'n'Rails injects a
     * {@code MixinCurvedTrackInteraction} into Create's {@code CurvedTrackInteraction.onClickInput}
     * that consumes empty-hand curve clicks (slab-encasement feature). Running before that
     * subscriber and cancelling the event when we actually fire keeps both Create's listener
     * and the Railways mixin from ever seeing a curve we want for grinding. We only cancel
     * when we've actually dispatched a grind start — if the player isn't wearing boots or
     * isn't looking at a rail, the event passes through to other mods normally.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (player.getMainHandItem().getItem() != Items.AIR) return;
        // Don't initiate a grind start while the player is seated on a mob, vehicle, or
        // Create seat — matches the canonical refusal in RailGrindHandler.railgrinding.
        if (player.isPassenger()) return;

        // Sub-level context comes first because sublevel teleports trip Sable's chunk-cache
        // disconnect — those clicks have to go through the proximity routine instead. The
        // generous 8-block probe catches any sublevel parked next to the player.
        boolean isSubLevelContext = Mods.SABLE.runIfInstalled(() -> () ->
                !SableSubLevels.sublevelsNear(player.level(), player.getEyePosition(), SUBLEVEL_PROBE_RADIUS).isEmpty()
        ).orElse(false);
        if (isSubLevelContext) {
            // Sublevel block hit (track block, including curve endpoints) first — vanilla
            // raycast covers it.
            String detection = detectStandardRailUnderClick(mc, player);
            // If no block hit registered, the click might still be on a sublevel curve
            // *segment* — those have no block, so vanilla MISS but our chunk walker can find
            // them in each sublevel's local frame. Create's TrackBlockOutline.result only
            // covers parent-world curves, so we can't reuse it here.
            if (detection == null && hasSubLevelCurveAtCrosshair(mc, player)) {
                detection = "sublevel curve segment";
            }
            updateRightClickDebug(mc, player, detection == null ? "<none, sublevel>" : "sublevel: " + detection);
            if (detection != null && ModEnchantments.isWearingRailGrindBoots(player)) {
                PacketDistributor.sendToServer(StartGrindFromNearestPayload.INSTANCE);
                player.swing(InteractionHand.MAIN_HAND);
                event.setCanceled(true);
            }
            return;
        }

        // Parent-world flow: compute the interaction-vector end (Vec3 the player aimed at)
        // for either a curve segment or a plain track block. Null = nothing rail-shaped.
        Vec3 interactionEnd = computeInteractionEnd(mc, player);
        if (interactionEnd == null) {
            updateRightClickDebug(mc, player, "<none>");
            return;
        }
        updateRightClickDebug(mc, player, "interactionEnd: " + interactionEnd);

        // Boots gate: tested AFTER detection so we don't cancel on rails the player can't
        // grind anyway — that'd needlessly suppress Railways' / Create's other curve
        // interactions. Without boots, leave the event for other mods.
        if (!ModEnchantments.isWearingRailGrindBoots(player)) return;

        // Server runs nearest-rail search rooted at interactionEnd, applies the rail-type
        // gate (isGrindableMaterial → STANDARD / narrow_gauge / wide_gauge, rejects phantom
        // and UNIVERSAL/MONORAIL), teleports the player onto the resolved rail, and starts
        // the grind.
        PacketDistributor.sendToServer(new Networking.TeleportToRailPacket(interactionEnd));
        player.swing(InteractionHand.MAIN_HAND);
        event.setCanceled(true);
    }

    /**
     * Returns the world position the player's crosshair lands on if it's a rail outline:
     * <ul>
     *   <li>Bezier curve segment → {@code TrackBlockOutline.result.vec()} (Create's own
     *       per-frame curve picker — the same field Create's {@code CurvedTrackInteraction}
     *       consumes for its in-hand interactions).</li>
     *   <li>Plain track block ({@link ITrackBlock}) at the vanilla hit position → the
     *       precise hit point ({@code bhr.getLocation()}). Material is gated by
     *       {@link RailGrindHandler#isGrindableMaterial} so phantom-variant or
     *       universal/monorail tracks return {@code null}.</li>
     * </ul>
     * Returns {@code null} if the crosshair isn't on a grindable rail of either kind.
     */
    @Nullable
    private static Vec3 computeInteractionEnd(Minecraft mc, LocalPlayer player) {
        BezierPointSelection createCurve = TrackBlockOutline.result;
        if (createCurve != null) return createCurve.vec();
        if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = bhr.getBlockPos();
            BlockState state = player.level().getBlockState(pos);
            if (state.getBlock() instanceof ITrackBlock track
                    && RailGrindHandler.isGrindableMaterial(track.getMaterial())) {
                return bhr.getLocation();
            }
        }
        return null;
    }

    /**
     * Tolerance for the sublevel curve walker — squared half-block radius from the look ray.
     */
    private static final double CURVE_PICK_MAX_DIST_SQ = 0.25;

    /**
     * True iff the player's crosshair is on a bezier curve segment in any nearby Sable
     * sublevel. Iterates each sublevel's TBE-bearing chunks in the sublevel's local frame
     * (Create's {@code TrackBlockOutline.pickCurves} only iterates the parent's
     * {@code TRACKS_WITH_TURNS} map, so it can't see sublevel curves — we have to walk them
     * ourselves). Used by the sublevel branch to extend rail-outline detection from "block
     * hit only" to also include curve segments, which have no collision and so produce a
     * {@code MISS} {@link HitResult} from the vanilla raycast.
     *
     * <p>Detection-only — returns boolean. The actual grind start is handed off to
     * {@link StartGrindFromNearestPayload}, whose server handler's
     * {@code findNearestRailLocation} re-walks the sublevels with the proper proximity
     * radius. Sublevels don't get the teleport flow because teleports adjacent to Sable
     * plot chunks have historically tripped the chunk-cache disconnect.
     */
    private static boolean hasSubLevelCurveAtCrosshair(Minecraft mc, LocalPlayer player) {
        if (!Mods.SABLE.isLoaded()) return false;
        Vec3 worldEye = player.getEyePosition();
        Vec3 worldLook = player.getViewVector(1.0F);
        double range = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) {
            range = Math.min(range, worldEye.distanceTo(mc.hitResult.getLocation()));
        }
        Vec3 worldTarget = worldEye.add(worldLook.scale(range));
        return Mods.SABLE.runIfInstalled(() -> () -> {
            for (SableSubLevels.SubLevelHandle handle : SableSubLevels.sublevelsNear(player.level(), worldEye, SUBLEVEL_PROBE_RADIUS)) {
                Level sl = handle.getLevel();
                if (sl == null) continue;
                Vec3 localEye = handle.toLocal(worldEye);
                Vec3 localTarget = handle.toLocal(worldTarget);
                if (curveOnRayInLevel(sl, localEye, localTarget)) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        }).orElse(Boolean.FALSE);
    }

    /**
     * Walks TBEs in a 3×3 chunk window around {@code eye}'s block position, AABB-tests each
     * bezier curve against the {@code eye → target} ray, then checks every segment for the
     * closest perpendicular distance to the ray. Returns true on the first segment within
     * {@link #CURVE_PICK_MAX_DIST_SQ}. {@code eye} and {@code target} must already be in
     * {@code level}'s coordinate frame.
     */
    private static boolean curveOnRayInLevel(Level level, Vec3 eye, Vec3 target) {
        BlockPos centerBlock = BlockPos.containing(eye);
        int chunkX = SectionPos.blockToSectionCoord(centerBlock.getX());
        int chunkZ = SectionPos.blockToSectionCoord(centerBlock.getZ());
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                ChunkAccess chunk = level.getChunk(chunkX + cx, chunkZ + cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (BlockPos bePos : chunk.getBlockEntitiesPos()) {
                    BlockEntity be = level.getBlockEntity(bePos);
                    if (!(be instanceof TrackBlockEntity tbe)) continue;
                    for (BezierConnection conn : tbe.getConnections().values()) {
                        AABB bounds = conn.getBounds();
                        if (!bounds.contains(eye) && bounds.clip(eye, target).isEmpty()) continue;
                        int segCount = conn.getSegmentCount();
                        for (int seg = 0; seg < segCount; seg++) {
                            float t = conn.getSegmentT(seg);
                            Vec3 p = conn.getPosition(t);
                            if (distancePointToSegmentSq(p, eye, target) < CURVE_PICK_MAX_DIST_SQ) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Squared perpendicular distance from {@code p} to the line segment {@code a → b},
     * clamped at the segment endpoints so points off either end don't register as close
     * just because their distance to the infinite line is small.
     */
    private static double distancePointToSegmentSq(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double abLenSq = ab.lengthSqr();
        if (abLenSq < 1.0e-9) return p.distanceToSqr(a);
        Vec3 ap = p.subtract(a);
        double t = Math.max(0.0, Math.min(1.0, ap.dot(ab) / abLenSq));
        Vec3 proj = a.add(ab.scale(t));
        return p.distanceToSqr(proj);
    }

    /**
     * Probe parent world + every nearby Sable sublevel (in several coordinate-frame
     * interpretations of {@code mc.hitResult}) for a standard track block at the click
     * target. Returns a short description of the first match (used for the debug overlay),
     * or null if no rail was found anywhere.
     *
     * <p>Why so many lookups: Sable's raycast mixin returns a {@link BlockHitResult} whose
     * {@code blockPos} / {@code location} frame is not consistent with the parent world — the
     * exact transform depends on which mixin path got hit. Rather than guess, we just try
     * every plausible interpretation and trust that whichever lookup hits the rail is the
     * right one. Cheap (a handful of {@code Level.getBlockState} calls).
     */
    @Nullable
    private static String detectStandardRailUnderClick(Minecraft mc, LocalPlayer player) {
        if (!(mc.hitResult instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos hitPos = bhr.getBlockPos();
        Vec3 hitVec = bhr.getLocation();
        Level world = player.level();

        // Parent world: same shape as the server's findRailBlockAt — hit block, then one
        // block below (handles clicks on the air block above a flat rail).
        String parent = railAtOrBelow(world, hitPos, "parent @ hitPos");
        if (parent != null) return parent;

        // Sub-levels: only when Sable is loaded. Mods.SABLE.runIfInstalled keeps the
        // SableSubLevels class load (and the Class.forName chain inside it) gated.
        return Mods.SABLE.runIfInstalled(() -> () -> {
            int idx = 0;
            for (SableSubLevels.SubLevelHandle h : SableSubLevels.sublevelsNear(world, player.getEyePosition(), SUBLEVEL_PROBE_RADIUS)) {
                idx++;
                Level sl = h.getLevel();
                if (sl == null) continue;
                String tag = "sublevel#" + idx;
                // Three coord-frame interpretations of the hit position — Sable's raycast
                // mixin doesn't guarantee which frame `bhr` came back in, so we try each:
                //   (a) blockPos as-is (assumed sublevel-local),
                //   (b) hitVec → local via the inverse pose,
                //   (c) blockPos center → local via the inverse pose.
                // Whichever finds a track wins; the descriptive tag is for the debug overlay.
                String r;
                if ((r = railAtOrBelow(sl, hitPos, tag + " @ hitPos (as-is)")) != null) return r;
                if ((r = railAtOrBelow(sl, BlockPos.containing(h.toLocal(hitVec)), tag + " @ toLocal(hitVec)")) != null) return r;
                if ((r = railAtOrBelow(sl, BlockPos.containing(h.toLocal(hitPos.getCenter())), tag + " @ toLocal(hitPos.center)")) != null) return r;
            }
            return null;
        }).orElse(null);
    }

    /**
     * If {@code level} has a standard track at {@code pos} or at {@code pos.below()}, returns
     * {@code tag} (suffixed with ".below" for the lower hit) — otherwise null. Mirrors the
     * pos-then-below shape of the server's {@code findRailBlockAt} so what right-clicks on a
     * parent-world rail also resolves on a sublevel rail.
     */
    @Nullable
    private static String railAtOrBelow(Level level, BlockPos pos, String tag) {
        if (isStandardRailAt(level, pos)) return tag;
        if (isStandardRailAt(level, pos.below())) return tag + ".below";
        return null;
    }

    private static boolean isStandardRailAt(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.getBlock() instanceof ITrackBlock t
                && RailGrindHandler.isGrindableMaterial(t.getMaterial());
    }

    /**
     * Capture the current raycast hit + detection result into {@link #lastRightClickInfo} for
     * the debug overlay. Always called from onClickInput regardless of whether we fire a
     * payload — that way the developer can see "I right-clicked but the rail wasn't found"
     * cases too.
     */
    private static void updateRightClickDebug(Minecraft mc, LocalPlayer player, String detectionSource) {
        BlockPos hitPos = null;
        Vec3 hitLoc = null;
        String hitType = "<none>";
        String parentBlockId = "<no block hit>";
        boolean parentIsStandardTrack = false;
        if (mc.hitResult != null) {
            hitType = mc.hitResult.getType().name();
            if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
                hitPos = bhr.getBlockPos();
                hitLoc = bhr.getLocation();
                BlockState s = player.level().getBlockState(hitPos);
                parentBlockId = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
                parentIsStandardTrack = s.getBlock() instanceof ITrackBlock t
                        && RailGrindHandler.isGrindableMaterial(t.getMaterial());
            }
        }
        long ts = mc.level == null ? 0L : mc.level.getGameTime();
        lastRightClickInfo = new LastRightClickInfo(
                ts, hitType, hitPos, hitLoc, parentBlockId, parentIsStandardTrack, detectionSource);
    }

    /**
     * Game-time tick at which the local player pressed jump while grinding, or -1 when not
     * currently charging. Set on jump-press while balancing, cleared on jump-release (after
     * sending the held-duration to the server) and on any non-jump grind exit (the per-tick
     * reset handler in onClientTickResetCharge). Read by the JumpChargeOverlay each frame to
     * compute the live bar fill, and on release to compute the held-tick count sent in
     * {@link StopGrindPayload}. We capture an absolute game tick rather than a counter so the
     * ratio is independent of frame-rate variance and renders consistently across partial-tick
     * frames.
     */
    private static long chargeStartGameTime = -1L;

    /** True while the local player is mid-jump-charge for a railgrind dismount. */
    public static boolean isCharging() {
        return chargeStartGameTime >= 0L;
    }

    /**
     * Live held-tick count for the in-progress charge, or -1 when not charging. Saturates at
     * Integer.MAX_VALUE rather than wrapping if the player somehow holds jump for billions of
     * ticks; the server clamps to JUMP_TRICK_CHARGE_INPUT_TIME_MAX anyway, so the only consumer
     * that sees the raw count is the overlay (which feeds it through the same clamping).
     */
    public static int getChargeHeldTicks() {
        if (chargeStartGameTime < 0L) return -1;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return -1;
        long held = mc.level.getGameTime() - chargeStartGameTime;
        if (held < 0L) return 0;
        return held > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) held;
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        // Mid-grind dismount only. The alternate jump+sneak start trigger lives in
        // onClientTick, since it needs to keep polling while both keys are held —
        // a single key-press event would only fire once.
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (event.getKey() != mc.options.keyJump.getKey().getValue()) return;

        if (event.getAction() == InputConstants.PRESS) {
            // Edge-trigger: only start charging on a fresh press while balancing. If the player
            // started a grind via the jump+sneak polling trigger with jump already down, no
            // PRESS event fires mid-grind, so the charge bar stays hidden until they release
            // and re-press jump. That matches the prior dismount-on-PRESS semantics — the
            // user has to deliberately press jump to commit to a dismount.
            if (!BalancingPoseTracker.isBalancing(player)) return;
            if (mc.level == null) return;
            chargeStartGameTime = mc.level.getGameTime();
        } else if (event.getAction() == InputConstants.RELEASE) {
            if (chargeStartGameTime < 0L) return;
            int held = getChargeHeldTicks();
            chargeStartGameTime = -1L;
            // Always send the dismount packet on release of a charge in progress, even if the
            // player has somehow stopped balancing in the interim — the server's stopWithLaunch
            // is a no-op in that case (ACTIVE.get returns null → falls through to plain stop()).
            PacketDistributor.sendToServer(new StopGrindPayload(Math.max(0, held)));
        }
    }

    /**
     * Cancels an in-progress charge if the grind ends for any reason other than jump release
     * (server kicks the player off, takes damage, runs off the end of the rail). Without this,
     * a left-over chargeStartGameTime would keep the overlay rendering and queue a stale
     * dismount packet on the next jump release.
     */
    @SubscribeEvent
    public static void onClientTickResetCharge(ClientTickEvent.Post event) {
        if (chargeStartGameTime < 0L) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !BalancingPoseTracker.isBalancing(player)) {
            chargeStartGameTime = -1L;
        }
    }

    /**
     * Continuously polls jump+sneak held while the player is *not* grinding and asks the
     * server to grind from the nearest rail to the player's feet. The server resolves the
     * actual target via {@link net.juniknytt.createrailgrinding.rail.RailGrindHandler#findNearestRailLocation}
     * — independent of the player's look angle, so a rail the player isn't aiming at (a curve
     * they're standing on, a track segment under their feet) is still a valid trigger.
     *
     * Sending happens every tick the combo is held — duplicate packets are harmless because
     * the server's {@code handleStartFromNearest} short-circuits when the player is already
     * grinding or still in cooldown, and the first successful packet flips the grinding flag.
     * Polling instead of key-press lets the player walk into a rail with both keys held and
     * have it grab as soon as one comes into range.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (BalancingPoseTracker.isBalancing(player)) return;
        // Sitting on a mob, boat, minecart, or Create seat — don't spam the server with a
        // start request that the canonical RailGrindHandler.railgrinding gate will refuse.
        if (player.isPassenger()) return;
        if (!isMountInputHeld(mc)) return;
        if (!ModEnchantments.isWearingRailGrindBoots(player)) return;
        PacketDistributor.sendToServer(StartGrindFromNearestPayload.INSTANCE);
    }

    /**
     * True iff the player is holding the configured mount-trigger this tick. The default trigger
     * is jump+sneak; when {@link Config#OVERRIDE_KEYBINDINGS} is enabled, the bound "Mount
     * Override" key replaces the combo. The override path short-circuits to false when the key is
     * unbound (KeyMapping with InputConstants.UNKNOWN reports {@code isDown() == false}, but we
     * also guard explicitly so a future change to that contract can't accidentally fire the trigger
     * every tick without any user input).
     */
    private static boolean isMountInputHeld(Minecraft mc) {
        if (Config.OVERRIDE_KEYBINDINGS.get()) {
            if (!ModKeyMappings.isMountOverrideBound()) return false;
            return ModKeyMappings.RAIL_MOUNT_OVERRIDE.isDown();
        }
        return mc.options.keyJump.isDown() && mc.options.keyShift.isDown();
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!BalancingPoseTracker.isBalancing(event.getEntity())) return;
        Input input = event.getInput();
        input.forwardImpulse = 0.0f;
        input.leftImpulse = 0.0f;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
    }

    /**
     * Most-recently-sent steer input (-1 left / 0 / +1 right). The packet only fires when this
     * value flips, so straight-line grinding is silent — packets only happen on key transitions.
     * Reset to 0 on dismount and on disconnect so a stale value can't survive a session boundary.
     */
    private static int lastSentSteer = 0;

    /**
     * Most-recently-sent accelerate-input mode ({@link GrindAccelInputPayload#VANILLA},
     * {@link GrindAccelInputPayload#OVERRIDE_OFF}, {@link GrindAccelInputPayload#OVERRIDE_ON}).
     * Edge-triggered like {@link #lastSentSteer}, so packets only fire when the mode flips. Reset
     * to VANILLA on session boundaries so the server's accel logic can never inherit a stale
     * OVERRIDE_ON from a previous session.
     */
    private static byte lastSentAccelMode = GrindAccelInputPayload.VANILLA;

    /**
     * Previous-tick value of {@code ChainConveyorRidingHandler.ridingChainConveyor != null}
     * for edge-triggering {@link #onChainMountTick}. Without this, the handler would either
     * fire a packet every tick the player is chain-riding (wasteful) or — if dropped to fire-
     * once-and-never-again — miss a re-mount inside the same session. Reset on session
     * boundaries via {@link #clearClientGrindState} so a stale {@code true} can't suppress
     * the first mount after a relog.
     */
    private static boolean lastChainMounted = false;

    /**
     * True while the client owes the server a {@link CrossDimGraceReleasePayload} for the
     * most recent silent (= cross-dim) grind-start sync. Set by
     * {@link net.juniknytt.createrailgrinding.network.ClientPayloadHandler#handleSync} the
     * moment a silent grind-start sync arrives, cleared by {@link #onCrossDimGraceAckTick}
     * the tick it sends the ack (or the tick the grind ends, whichever comes first). The
     * tick handler polls each post-tick for mc.player + mc.level + chunk-at-player-position
     * all live, then fires the payload exactly once. Without this gate the server's
     * trigger-based grace would have to fall back to its hard timeout for every cross-dim
     * traversal — too long on fast clients, sometimes still too short on slow ones.
     */
    public static volatile boolean pendingCrossDimGraceAck = false;

    /**
     * Marks that a silent grind-start sync just arrived and the client should ack readiness
     * to the server as soon as the LocalPlayer + level + chunks are live. Called from the
     * sync payload handler.
     */
    public static void requestCrossDimGraceAck() {
        pendingCrossDimGraceAck = true;
    }

    /**
     * Edge-triggered handoff signal. As soon as Create's client-side embark sets
     * {@code ChainConveyorRidingHandler.ridingChainConveyor}, fire one payload to the server
     * so the active grind drops on the next server tick instead of waiting on Create's
     * 10-tick TTL packet to surface in {@code ServerChainConveyorHandler.hangingPlayers}.
     * Gated on {@link BalancingPoseTracker#isBalancing} so a wrench-mount that happens
     * outside a grind never sends a packet.
     */
    @SubscribeEvent
    public static void onChainMountTick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            lastChainMounted = false;
            return;
        }
        boolean mounted = ChainConveyorRidingHandler.ridingChainConveyor != null;
        if (mounted && !lastChainMounted && BalancingPoseTracker.isBalancing(player)) {
            PacketDistributor.sendToServer(ChainMountedPayload.INSTANCE);
        }
        lastChainMounted = mounted;
    }

    /**
     * Cross-dim grace-release ack pump. When the server starts a re-grind on the destination
     * side it ships a silent grind-start sync (handled by {@link
     * net.juniknytt.createrailgrinding.network.ClientPayloadHandler#handleSync}) which flips
     * {@link #pendingCrossDimGraceAck} to true. The server holds {@code gs.position} still
     * until this client confirms it's actually ready to chase a moving target — chunks loaded
     * around the player's new position, LocalPlayer attached to the new level, etc. Each
     * post-tick we re-check those gates and fire the ack exactly once when they all pass.
     *
     * <p>Readiness gates (target = "client can send movement + input packets normally"):
     * <ul>
     *   <li>{@code mc.player != null} — the new LocalPlayer has been created by handleRespawn.</li>
     *   <li>{@code mc.level != null} — the new ClientLevel is attached.</li>
     *   <li>{@code BalancingPoseTracker.isBalancing(player)} — the silent sync was actually
     *       processed (not just observed by a stray non-grinding sync). Drops the flag if the
     *       grind ended on the client before the gate cleared.</li>
     *   <li>{@code !(mc.screen instanceof ReceivingLevelScreen)} — the vanilla "Receiving
     *       level..." loading screen has been dismissed. While it's up, the client may be
     *       running LocalPlayer.tick() but isn't yet in a player-controllable state — input
     *       is blocked and the client doesn't render the world. The canonical "client is
     *       ready for movement/input" signal in 1.21.1. <b>Do not remove:</b> a 2026-05-13
     *       attempt to drop this gate "to fix the visual hard-snap repetition on remote MP
     *       clients" caused MORE drops post-cross-dim including on the host — the ACK fired
     *       too early, server collapsed grace before client-server position alignment was
     *       complete, and MAX_DRIFT tripped post-grace.</li>
     *   <li>{@code mc.level.isLoaded(player.blockPosition())} — the chunk under the player
     *       has reached FULL status on the client. Belt-and-suspenders alongside the loading-
     *       screen check (the screen typically dismisses once chunks are streamed, but mod-
     *       triggered respawns / fast-disk hosts can dismiss before all neighboring chunks
     *       are at FULL, so the explicit check stays).</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onCrossDimGraceAckTick(ClientTickEvent.Post event) {
        if (!pendingCrossDimGraceAck) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        if (!BalancingPoseTracker.isBalancing(player)) {
            // Grind ended on the client before we could ack — abandon the pending ack so a
            // future grind start doesn't inherit a stale signal.
            pendingCrossDimGraceAck = false;
            return;
        }
        // Wait until the loading screen is gone — that's the canonical signal that the
        // client is ready to send movement and input packets normally. While the screen
        // is up the client still ticks but blocks input.
        if (mc.screen instanceof ReceivingLevelScreen) return;
        if (!mc.level.isLoaded(player.blockPosition())) return;
        // All gates passed — ship the ack and clear the flag. Server's
        // releaseReattachGrace is idempotent so a stray repeat is harmless, but we drop the
        // flag here so we don't waste packets on every subsequent tick of the grace window.
        PacketDistributor.sendToServer(CrossDimGraceReleasePayload.INSTANCE);
        pendingCrossDimGraceAck = false;
    }

    /**
     * Polls the strafe keys (vanilla keyLeft / keyRight, A/D by default) while grinding and
     * sends the resulting steer sign to the server whenever it changes. The server feeds the
     * value to RailGrindHandler.advanceJunction, which uses the same lateral-projection
     * algorithm Create's TravellingPoint.steer applies to player-controlled trains: at
     * intersections, pick the exit whose tangent is most aligned with the player's intent.
     *
     * Reading the key bindings directly (rather than input.leftImpulse) means {@link
     * #onMovementInput}'s zeroing of leftImpulse — which exists to suppress strafe motion and
     * animation while grinding — doesn't also kill our steer signal.
     */
    @SubscribeEvent
    public static void onSteerTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        int steer = 0;
        boolean balancing = player != null && BalancingPoseTracker.isBalancing(player);
        if (balancing) {
            if (mc.options.keyLeft.isDown())  steer -= 1;
            if (mc.options.keyRight.isDown()) steer += 1;
        }
        // Self-publish the raw lean sign to the local tracker every tick while balancing,
        // independent of the C2S packet edge. Drives the local player's lean visual (model
        // tilt + optional camera roll + camera lateral offset) directly from key state, no
        // round-trip lag. Skip when not balancing so the tick handler's cleanup sweep can
        // drop us cleanly from RAW.
        if (balancing) {
            RailGrindLeanTracker.setRawSign(player.getUUID(), steer);
        }
        if (steer == lastSentSteer) return;
        // No connection guard — sendToServer is a no-op if the player isn't connected.
        if (player != null) PacketDistributor.sendToServer(new SteerInputPayload((byte) steer));
        lastSentSteer = steer;
    }

    /**
     * Polls the accelerate-input source and tells the server which one to consult. Edge-triggered
     * like {@link #onSteerTick} — only sends on mode flip.
     *
     * <p>Mode resolution: when overrides are enabled in config and the override key is bound,
     * accel state derives from the override key (OVERRIDE_ON / OVERRIDE_OFF). Otherwise the
     * server falls back to {@code player.isShiftKeyDown()} via the VANILLA mode.
     *
     * <p>Polled every tick (not just while balancing) so that a config or rebind change while
     * the player is mid-grind takes effect on the next tick — leaving stale OVERRIDE_* on the
     * server after the user disables config in the middle of a grind would otherwise pin them
     * into not-accelerating regardless of shift state.
     */
    @SubscribeEvent
    public static void onAccelInputTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        byte mode;
        if (Config.OVERRIDE_KEYBINDINGS.get() && ModKeyMappings.isAccelOverrideBound()) {
            mode = ModKeyMappings.GRIND_CROUCH_ACCELERATE_OVERRIDE.isDown()
                    ? GrindAccelInputPayload.OVERRIDE_ON
                    : GrindAccelInputPayload.OVERRIDE_OFF;
        } else {
            mode = GrindAccelInputPayload.VANILLA;
        }
        if (mode == lastSentAccelMode) return;
        PacketDistributor.sendToServer(new GrindAccelInputPayload(mode));
        lastSentAccelMode = mode;
    }

    /**
     * Wipe stale client-side grind state on world join/leave/respawn. Without this,
     * a UUID left in {@link BalancingPoseTracker} from the previous session re-applies
     * the T-pose (and in some cases keeps {@code noPhysics} set on a freshly loaded
     * LocalPlayer) the moment the player rejoins.
     */
    @SubscribeEvent
    public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        clearClientGrindState(event.getPlayer());
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clearClientGrindState(event.getPlayer());
    }

    @SubscribeEvent
    public static void onClientClone(ClientPlayerNetworkEvent.Clone event) {
        // Fires on respawn / dimension change. Distinguish the two: respawn-after-death
        // and end-credits-exit must wipe state (the old GrindState is gone server-side and
        // a stale BalancingPoseTracker UUID would keep PlayerNoPhysicsTickMixin firing on a
        // dead-then-reborn player). Cross-dim transit while still grinding must NOT wipe
        // state — the server has already queued a re-grind via finishCrossDimRegrind, and
        // the 5+ tick wait window before the silent sync arrives would otherwise leave the
        // new LocalPlayer with an empty BalancingPoseTracker → mixin dormant → vanilla
        // physics on every tick. Server's setPos pinning corrects the desync visually each
        // server tick, but the client renders the gap as a teleport-jitter rather than a
        // smooth continuation of the grind. Preserving BalancingPoseTracker keeps the
        // mixin live so noPhysics stays asserted until the silent sync confirms the new
        // grind state. Stale motion target / sounds / input still get cleared in the
        // cross-dim branch — the old-dim Vec3 coords are meaningless in the new dim and
        // the next RailGrindTargetPayload (serverAuthoritative=true) refreshes them.
        LocalPlayer oldPlayer = event.getOldPlayer();
        LocalPlayer newPlayer = event.getNewPlayer();
        if (isCrossDimTransit(oldPlayer, newPlayer)) {
            clearCrossDimEphemeralState();
            return;
        }
        clearClientGrindState(newPlayer);
    }

    /**
     * Cross-dim Clone signature: both players exist, both have a level, dimensions differ.
     * Defensive null-checks because the old LocalPlayer is mid-disconnect at this point and
     * a future Mojang refactor could null its level reference before Clone fires.
     */
    private static boolean isCrossDimTransit(@Nullable LocalPlayer oldPlayer, @Nullable LocalPlayer newPlayer) {
        if (oldPlayer == null || newPlayer == null) return false;
        Level oldLevel = oldPlayer.level();
        Level newLevel = newPlayer.level();
        if (oldLevel == null || newLevel == null) return false;
        return !oldLevel.dimension().equals(newLevel.dimension());
    }

    /**
     * Cross-dim-only subset of {@link #clearClientGrindState}. Drops stale-after-transit
     * state (sound positions, motion target in old-dim coords, edge-triggered input flags)
     * but PRESERVES BalancingPoseTracker / pendingCrossDimGraceAck / LocalPlayer.noPhysics
     * so the new LocalPlayer continues being treated as a grinding player by every other
     * subsystem until the server's silent sync packet arrives ~5 ticks later.
     */
    private static void clearCrossDimEphemeralState() {
        GrindSoundController.clearAll();
        RailGrindClientMotion.clearTarget();
        lastSentSteer = 0;
        lastSentAccelMode = GrindAccelInputPayload.VANILLA;
        lastChainMounted = false;
        chargeStartGameTime = -1L;
    }

    private static void clearClientGrindState(LocalPlayer player) {
        BalancingPoseTracker.clear();
        GrindSoundController.clearAll();
        RailGrindClientMotion.clearTarget();
        RailGrindLeanTracker.clearAll();
        lastSentSteer = 0;
        lastSentAccelMode = GrindAccelInputPayload.VANILLA;
        lastChainMounted = false;
        chargeStartGameTime = -1L;
        // Drop any pending grace-release ack — a session boundary means the server-side
        // GrindState the ack would have applied to is gone, so the flag is stale. The
        // next cross-dim grind start (post-rejoin) will set it fresh via the sync handler.
        pendingCrossDimGraceAck = false;
        if (player != null) {
            player.noPhysics = false;
        }
    }
}
