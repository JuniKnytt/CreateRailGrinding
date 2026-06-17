package net.juniknytt.createrailgrinding.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorRidingHandler;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackBlockOutline;
import com.simibubi.create.content.trains.track.TrackBlockOutline.BezierPointSelection;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.enchantment.ModEnchantments;
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
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.juniknytt.createrailgrinding.network.ModNetworking;

@Mod.EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public class ClientInputHandler {

    public record LastRightClickInfo(
            long gameTimeStamp,
            String hitType,
            @Nullable BlockPos hitBlockPos,
            @Nullable Vec3 hitLocation,
            String parentBlockId,
            boolean parentIsStandardTrack,
            String detectionSource
    ) {}

    public static volatile @Nullable LastRightClickInfo lastRightClickInfo = null;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        if (isInputNeutralized(player)) return;
        if (player.getMainHandItem().getItem() != Items.AIR) return;

        if (player.isPassenger()) return;

        Vec3 interactionEnd = computeInteractionEnd(mc, player);
        if (interactionEnd == null) {
            updateRightClickDebug(mc, player, "<none>");
            return;
        }
        updateRightClickDebug(mc, player, "interactionEnd: " + interactionEnd);

        if (!ModEnchantments.isWearingRailGrindBoots(player)) return;

        ModNetworking.toServer(new Networking.TeleportToRailPacket(interactionEnd));
        player.swing(InteractionHand.MAIN_HAND);
        event.setCanceled(true);
    }

    @Nullable
    private static Vec3 computeInteractionEnd(Minecraft mc, LocalPlayer player) {
        if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = bhr.getBlockPos();
            BlockState state = player.level().getBlockState(pos);
            if (state.getBlock() instanceof ITrackBlock track
                    && RailGrindHandler.isGrindableMaterial(track.getMaterial())) {
                return bhr.getLocation();
            }
        }

        Vec3 eye = player.getEyePosition();
        double range = Math.max(player.getBlockReach(), GRIND_START_CURVE_REACH);
        Vec3 target = eye.add(player.getViewVector(1.0F).scale(range));
        Vec3 ours = RailGrindHandler.pickGrindableCurvePointOnRay(player.level(), eye, target, CURVE_PICK_MAX_DIST_SQ);
        if (ours != null) return ours;

        BezierPointSelection createCurve = TrackBlockOutline.result;
        if (createCurve != null) {
            Vec3 v = createCurve.vec();
            if (v != null && v.distanceToSqr(player.position()) <= GRIND_START_MAX_TELEPORT_DIST_SQ) return v;
        }
        return null;
    }

    private static final double CURVE_PICK_MAX_DIST_SQ = 0.25;
    private static final double GRIND_START_CURVE_REACH = 8.0;
    private static final double GRIND_START_MAX_TELEPORT_DIST_SQ = 100.0;

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

    private static long chargeStartGameTime = -1L;

    public static boolean isCharging() {
        return chargeStartGameTime >= 0L;
    }

    public static int getChargeHeldTicks() {
        if (chargeStartGameTime < 0L) return -1;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return -1;
        long held = mc.level.getGameTime() - chargeStartGameTime;
        if (held < 0L) return 0;
        return held > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) held;
    }

    private static boolean prevJumpInput = false;

    @SubscribeEvent
    public static void onJumpEdgeTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            prevJumpInput = false;
            return;
        }

        if (mc.screen != null || isInputNeutralized(player)) {
            prevJumpInput = false;
            return;
        }
        boolean jump = isBindingHeld(ModKeyMappings.GRIND_JUMP);
        boolean pressed  =  jump && !prevJumpInput;
        boolean released = !jump &&  prevJumpInput;
        prevJumpInput = jump;
        if (pressed) {

            if (!BalancingPoseTracker.isBalancing(player)) return;
            if (mc.level == null) return;
            chargeStartGameTime = mc.level.getGameTime();
        } else if (released) {
            if (chargeStartGameTime < 0L) return;
            int held = getChargeHeldTicks();
            chargeStartGameTime = -1L;

            ModNetworking.toServer(new StopGrindPayload(Math.max(0, held)));
            mc.gui.setOverlayMessage(Component.translatable(
                    "createrailgrinding.catch_prompt",
                    ModKeyMappings.CATCH.getTranslatedKeyMessage()), false);
        }
    }

    @SubscribeEvent
    public static void onClientTickResetCharge(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (chargeStartGameTime < 0L) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null
                || !BalancingPoseTracker.isBalancing(player)
                || isInputNeutralized(player)) {

            chargeStartGameTime = -1L;
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (BalancingPoseTracker.isBalancing(player)) return;

        if (player.isPassenger()) return;
        if (!isMountInputHeld(player)) return;
        if (!ModEnchantments.isWearingRailGrindBoots(player)) return;
        ModNetworking.toServer(StartGrindFromNearestPayload.INSTANCE);
    }

    private static boolean isMountInputHeld(LocalPlayer player) {
        if (isInputNeutralized(player)) return false;
        return isBindingHeld(ModKeyMappings.CATCH);
    }

    private static boolean isBindingHeld(net.minecraft.client.KeyMapping mapping) {
        if (mapping.isUnbound()) return false;
        if (!mapping.getKeyConflictContext().isActive()) return false;
        if (!mapping.getKeyModifier().isActive(mapping.getKeyConflictContext())) return false;
        InputConstants.Key key = mapping.getKey();
        long window = Minecraft.getInstance().getWindow().getWindow();
        return switch (key.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(window, key.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
            default -> mapping.isDown();
        };
    }

    public static boolean isAccelerateHeld(LocalPlayer player) {
        if (isInputNeutralized(player)) return false;
        return isBindingHeld(ModKeyMappings.GRIND_CROUCH);
    }

    private static volatile int capturedSteerInput = 0;

    public static int getSteerInput() {
        return capturedSteerInput;
    }

    private static boolean isInputNeutralized(LocalPlayer player) {
        return !(player.input instanceof KeyboardInput);
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        Input input = event.getInput();

        capturedSteerInput =
                (input.left  ? -1 : 0)
              + (input.right ? +1 : 0);

        if (!BalancingPoseTracker.isBalancing(event.getEntity())) return;

        input.forwardImpulse = 0.0f;
        input.leftImpulse = 0.0f;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
    }

    private static int lastSentSteer = 0;

    private static byte lastSentAccelMode = GrindAccelInputPayload.VANILLA;

    private static boolean lastChainMounted = false;

    public static volatile boolean pendingCrossDimGraceAck = false;

    public static void requestCrossDimGraceAck() {
        pendingCrossDimGraceAck = true;
    }

    @SubscribeEvent
    public static void onChainMountTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            lastChainMounted = false;
            return;
        }
        boolean mounted = ChainConveyorRidingHandler.ridingChainConveyor != null;
        if (mounted && !lastChainMounted && BalancingPoseTracker.isBalancing(player)) {
            ModNetworking.toServer(ChainMountedPayload.INSTANCE);
        }
        lastChainMounted = mounted;
    }

    @SubscribeEvent
    public static void onCrossDimGraceAckTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!pendingCrossDimGraceAck) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        if (!BalancingPoseTracker.isBalancing(player)) {

            pendingCrossDimGraceAck = false;
            return;
        }

        if (mc.screen instanceof ReceivingLevelScreen) return;
        if (!mc.level.isLoaded(player.blockPosition())) return;

        ModNetworking.toServer(CrossDimGraceReleasePayload.INSTANCE);
        pendingCrossDimGraceAck = false;
    }

    @SubscribeEvent
    public static void onSteerTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        int steer = 0;
        boolean balancing = player != null && BalancingPoseTracker.isBalancing(player);
        if (balancing) {
            steer = capturedSteerInput;
        }

        if (balancing) {
            RailGrindLeanTracker.setRawSign(player.getUUID(), steer);
        }
        if (steer == lastSentSteer) return;

        if (player != null) ModNetworking.toServer(new SteerInputPayload((byte) steer));
        lastSentSteer = steer;
    }

    @SubscribeEvent
    public static void onAccelInputTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        boolean balancing = BalancingPoseTracker.isBalancing(player);
        boolean held = balancing && isAccelerateHeld(player);
        RailGrindAccelTracker.setAccelerating(player.getUUID(), held);

        if (!balancing) {

            lastSentAccelMode = GrindAccelInputPayload.VANILLA;
            return;
        }

        byte mode = held ? GrindAccelInputPayload.OVERRIDE_ON : GrindAccelInputPayload.OVERRIDE_OFF;
        if (mode == lastSentAccelMode) return;
        ModNetworking.toServer(new GrindAccelInputPayload(mode));
        lastSentAccelMode = mode;
    }

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

        LocalPlayer oldPlayer = event.getOldPlayer();
        LocalPlayer newPlayer = event.getNewPlayer();
        if (isCrossDimTransit(oldPlayer, newPlayer)) {
            clearCrossDimEphemeralState();
            return;
        }
        clearClientGrindState(newPlayer);
    }

    private static boolean isCrossDimTransit(@Nullable LocalPlayer oldPlayer, @Nullable LocalPlayer newPlayer) {
        if (oldPlayer == null || newPlayer == null) return false;
        Level oldLevel = oldPlayer.level();
        Level newLevel = newPlayer.level();
        if (oldLevel == null || newLevel == null) return false;
        return !oldLevel.dimension().equals(newLevel.dimension());
    }

    private static void clearCrossDimEphemeralState() {
        GrindSoundController.clearAll();
        RailGrindClientMotion.clearTarget();
        lastSentSteer = 0;
        lastSentAccelMode = GrindAccelInputPayload.VANILLA;
        lastChainMounted = false;
        chargeStartGameTime = -1L;
        prevJumpInput = false;
        capturedSteerInput = 0;
    }

    private static void clearClientGrindState(LocalPlayer player) {
        BalancingPoseTracker.clear();
        GrindSoundController.clearAll();
        RailGrindClientMotion.clearTarget();
        RailGrindLeanTracker.clearAll();
        RailGrindAccelTracker.clearAll();
        lastSentSteer = 0;
        lastSentAccelMode = GrindAccelInputPayload.VANILLA;
        lastChainMounted = false;
        chargeStartGameTime = -1L;
        prevJumpInput = false;
        capturedSteerInput = 0;

        pendingCrossDimGraceAck = false;
        if (player != null) {
            player.noPhysics = false;
        }
    }
}
