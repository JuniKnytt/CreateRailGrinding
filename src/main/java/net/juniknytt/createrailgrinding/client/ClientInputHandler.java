package net.juniknytt.createrailgrinding.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.simibubi.create.content.equipment.armor.DivingBootsItem;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockOutline;
import com.simibubi.create.content.trains.track.TrackBlockOutline.BezierPointSelection;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.network.Networking;
import net.juniknytt.createrailgrinding.network.StartGrindFromNearestPayload;
import net.juniknytt.createrailgrinding.network.SteerInputPayload;
import net.juniknytt.createrailgrinding.network.StopGrindPayload;
import net.juniknytt.createrailgrinding.sound.GrindSoundController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public class ClientInputHandler {

    @SubscribeEvent
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (player.getMainHandItem().getItem() != Items.AIR) return;
        if (!(player.getItemBySlot(EquipmentSlot.FEET).getItem() instanceof DivingBootsItem)) return;

        Networking.TeleportToRailPacket packet = pickGrindTarget(mc, player);
        if (packet == null) return;

        PacketDistributor.sendToServer(packet);
        player.swing(InteractionHand.MAIN_HAND);
        event.setCanceled(true);
    }

    private static Networking.TeleportToRailPacket pickGrindTarget(Minecraft mc, LocalPlayer player) {
        // Bezier curve hit: send the click point on the curve plus the bezier graph hint
        // (endpoint + segment). The server resolves it via TrackGraphHelper.getBezierGraphLocationAt
        // so the grind starts at the clicked spot on the curve rather than snapping to an endpoint.
        BezierPointSelection bezier = TrackBlockOutline.result;
        if (bezier != null) {
            Networking.TeleportToRailPacket.BezierTarget bezierTarget =
                    new Networking.TeleportToRailPacket.BezierTarget(
                            bezier.blockEntity().getBlockPos(), bezier.loc());
            return new Networking.TeleportToRailPacket(bezier.vec(), bezierTarget);
        }
        // Plain track block hit (straight, diagonal, slope): use the block itself
        if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = bhr.getBlockPos();
            BlockState state = player.level().getBlockState(pos);
            if (state.getBlock() instanceof ITrackBlock track
                    && track.getMaterial().trackType == TrackMaterial.TrackType.STANDARD) {
                return new Networking.TeleportToRailPacket(pos.getBottomCenter(), null);
            }
        }
        return null;
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
        if (!mc.options.keyJump.isDown()) return;
        if (!mc.options.keyShift.isDown()) return;
        if (!(player.getItemBySlot(EquipmentSlot.FEET).getItem() instanceof DivingBootsItem)) return;
        PacketDistributor.sendToServer(StartGrindFromNearestPayload.INSTANCE);
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
        if (player != null && BalancingPoseTracker.isBalancing(player)) {
            if (mc.options.keyLeft.isDown())  steer -= 1;
            if (mc.options.keyRight.isDown()) steer += 1;
        }
        if (steer == lastSentSteer) return;
        // No connection guard — sendToServer is a no-op if the player isn't connected.
        if (player != null) PacketDistributor.sendToServer(new SteerInputPayload((byte) steer));
        lastSentSteer = steer;
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
        // Fires on respawn / dimension change. New LocalPlayer instance — clear too.
        clearClientGrindState(event.getNewPlayer());
    }

    private static void clearClientGrindState(LocalPlayer player) {
        BalancingPoseTracker.clear();
        GrindSoundController.clearAll();
        lastSentSteer = 0;
        chargeStartGameTime = -1L;
        if (player != null) {
            player.noPhysics = false;
        }
    }
}
