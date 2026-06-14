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
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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
import org.lwjgl.glfw.GLFW;
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

    private static final double SUBLEVEL_PROBE_RADIUS = 8.0;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        if (isInputNeutralized(player)) return;
        if (player.getMainHandItem().getItem() != Items.AIR) return;

        if (player.isPassenger()) return;

        boolean isSubLevelContext = Mods.SABLE.runIfInstalled(() -> () ->
                !SableSubLevels.sublevelsNear(player.level(), player.getEyePosition(), SUBLEVEL_PROBE_RADIUS).isEmpty()
        ).orElse(false);
        if (isSubLevelContext) {

            String detection = detectStandardRailUnderClick(mc, player);

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

        Vec3 interactionEnd = computeInteractionEnd(mc, player);
        if (interactionEnd == null) {
            updateRightClickDebug(mc, player, "<none>");
            return;
        }
        updateRightClickDebug(mc, player, "interactionEnd: " + interactionEnd);

        if (!ModEnchantments.isWearingRailGrindBoots(player)) return;

        PacketDistributor.sendToServer(new Networking.TeleportToRailPacket(interactionEnd));
        player.swing(InteractionHand.MAIN_HAND);
        event.setCanceled(true);
    }

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

        Vec3 eye = player.getEyePosition();
        double range = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) {
            range = Math.min(range, eye.distanceTo(mc.hitResult.getLocation()));
        }
        Vec3 target = eye.add(player.getViewVector(1.0F).scale(range));
        return RailGrindHandler.pickGrindableCurvePointOnRay(player.level(), eye, target, CURVE_PICK_MAX_DIST_SQ);
    }

    private static final double CURVE_PICK_MAX_DIST_SQ = 0.25;

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

    private static double distancePointToSegmentSq(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double abLenSq = ab.lengthSqr();
        if (abLenSq < 1.0e-9) return p.distanceToSqr(a);
        Vec3 ap = p.subtract(a);
        double t = Math.max(0.0, Math.min(1.0, ap.dot(ab) / abLenSq));
        Vec3 proj = a.add(ab.scale(t));
        return p.distanceToSqr(proj);
    }

    @Nullable
    private static String detectStandardRailUnderClick(Minecraft mc, LocalPlayer player) {
        if (!(mc.hitResult instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos hitPos = bhr.getBlockPos();
        Vec3 hitVec = bhr.getLocation();
        Level world = player.level();

        String parent = railAtOrBelow(world, hitPos, "parent @ hitPos");
        if (parent != null) return parent;

        return Mods.SABLE.runIfInstalled(() -> () -> {
            int idx = 0;
            for (SableSubLevels.SubLevelHandle h : SableSubLevels.sublevelsNear(world, player.getEyePosition(), SUBLEVEL_PROBE_RADIUS)) {
                idx++;
                Level sl = h.getLevel();
                if (sl == null) continue;
                String tag = "sublevel#" + idx;

                String r;
                if ((r = railAtOrBelow(sl, hitPos, tag + " @ hitPos (as-is)")) != null) return r;
                if ((r = railAtOrBelow(sl, BlockPos.containing(h.toLocal(hitVec)), tag + " @ toLocal(hitVec)")) != null) return r;
                if ((r = railAtOrBelow(sl, BlockPos.containing(h.toLocal(hitPos.getCenter())), tag + " @ toLocal(hitPos.center)")) != null) return r;
            }
            return null;
        }).orElse(null);
    }

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
    public static void onJumpEdgeTick(ClientTickEvent.Post event) {
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

            PacketDistributor.sendToServer(new StopGrindPayload(Math.max(0, held)));
            mc.gui.setOverlayMessage(Component.translatable(
                    "createrailgrinding.catch_prompt",
                    ModKeyMappings.CATCH.getTranslatedKeyMessage()), false);
        }
    }

    @SubscribeEvent
    public static void onClientTickResetCharge(ClientTickEvent.Post event) {
        if (chargeStartGameTime < 0L) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null
                || !BalancingPoseTracker.isBalancing(player)
                || isInputNeutralized(player)) {

            chargeStartGameTime = -1L;
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (BalancingPoseTracker.isBalancing(player)) return;

        if (player.isPassenger()) return;
        if (!isMountInputHeld(player)) return;
        if (!ModEnchantments.isWearingRailGrindBoots(player)) return;
        PacketDistributor.sendToServer(StartGrindFromNearestPayload.INSTANCE);
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

    @SubscribeEvent
    public static void onCrossDimGraceAckTick(ClientTickEvent.Post event) {
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

        PacketDistributor.sendToServer(CrossDimGraceReleasePayload.INSTANCE);
        pendingCrossDimGraceAck = false;
    }

    @SubscribeEvent
    public static void onSteerTick(ClientTickEvent.Post event) {
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

        if (player != null) PacketDistributor.sendToServer(new SteerInputPayload((byte) steer));
        lastSentSteer = steer;
    }

    @SubscribeEvent
    public static void onAccelInputTick(ClientTickEvent.Post event) {
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
        PacketDistributor.sendToServer(new GrindAccelInputPayload(mode));
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
