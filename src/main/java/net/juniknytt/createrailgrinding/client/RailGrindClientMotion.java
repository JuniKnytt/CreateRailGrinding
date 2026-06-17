package net.juniknytt.createrailgrinding.client;

import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.network.BlockedByObstaclePayload;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.juniknytt.createrailgrinding.network.ModNetworking;
import org.jetbrains.annotations.Nullable;

@Mod.EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public final class RailGrindClientMotion {

    private static final double MAX_STEP = 2.0;

    private static final double CORRECTION_RATE = 0.3;

    private static final double BLOCKED_RATIO = 0.5;

    private static final int BLOCKED_DROP_TICKS = 3;

    private static final double MIN_INTENT = 0.1;

    private static final double BB_BOTTOM_TRIM = 0.5;

    private static final double FORWARD_PROBE_MIN = 0.5;

    private static final double FORWARD_PROBE_MAX = 1.0;

    private static volatile @Nullable Vec3 target = null;
    private static volatile @Nullable Vec3 velocity = null;
    private static volatile boolean serverAuthoritative = false;
    private static @Nullable Vec3 smoothedTarget = null;

    private static @Nullable Vec3 lastPlayerPos = null;

    private static @Nullable Vec3 lastIntent = null;

    private static int blockedTicks = 0;

    private static boolean blockedDispatched = false;

    private static volatile boolean obstacleAheadOnSlope = false;

    private RailGrindClientMotion() {}

    public static void setTargetAndVelocity(Vec3 nextTarget, Vec3 nextVelocity, boolean authoritative) {
        target = nextTarget;
        velocity = nextVelocity;
        serverAuthoritative = authoritative;
    }

    public static void clearTarget() {
        target = null;
        velocity = null;
        serverAuthoritative = false;
        smoothedTarget = null;
        resetBlockedDetectionState();
        blockedDispatched = false;
        obstacleAheadOnSlope = false;
    }

    @SubscribeEvent
    public static void onClientTickPre(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (mc.isPaused()) {
            resetBlockedDetectionState();
            return;
        }
        if (!BalancingPoseTracker.isBalancing(player)) {

            if (target != null || smoothedTarget != null) clearTarget();
            return;
        }
        Vec3 currentTarget = target;
        if (currentTarget == null) return;
        Vec3 currentVelocity = velocity;
        if (currentVelocity == null) currentVelocity = Vec3.ZERO;

        runBlockedDetection(player);

        if (serverAuthoritative) {
            player.setPos(currentTarget.x, currentTarget.y, currentTarget.z);
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            smoothedTarget = currentTarget;

            resetBlockedDetectionState();
            blockedDispatched = false;
            return;
        }

        if (smoothedTarget == null) {

            smoothedTarget = currentTarget;
        } else {

            Vec3 predicted = smoothedTarget.add(currentVelocity);

            Vec3 gap = currentTarget.subtract(predicted);
            smoothedTarget = predicted.add(gap.scale(CORRECTION_RATE));
        }

        Vec3 diff = smoothedTarget.subtract(player.position());
        double mag = diff.length();
        if (mag > MAX_STEP) {
            diff = diff.scale(MAX_STEP / mag);
        }

        lastIntent = diff;
        lastPlayerPos = player.position();

        player.setDeltaMovement(diff);

        player.fallDistance = 0.0F;
    }

    private static void runBlockedDetection(LocalPlayer player) {

        obstacleAheadOnSlope = false;

        if (lastPlayerPos == null || lastIntent == null) return;
        double intentLen = lastIntent.length();
        if (intentLen < MIN_INTENT) {
            blockedTicks = 0;
            return;
        }

        Vec3 dm = player.getDeltaMovement();
        double dmLen = dm.length();
        double slope = dmLen > 1e-3 ? dm.y / dmLen : 0.0;
        boolean noPhysicsRegime = Math.abs(slope) > RailGrindHandler.NO_PHYSICS_SLOPE_THRESHOLD;

        boolean tickBlocked;
        if (noPhysicsRegime) {

            Vec3 forward = lastIntent.scale(1.0 / intentLen);
            double probeDist = Math.max(FORWARD_PROBE_MIN, Math.min(FORWARD_PROBE_MAX, intentLen));
            AABB pbb = player.getBoundingBox();
            AABB trimmed = new AABB(
                    pbb.minX, pbb.minY + BB_BOTTOM_TRIM, pbb.minZ,
                    pbb.maxX, pbb.maxY,                  pbb.maxZ);
            AABB probe = trimmed.move(forward.x * probeDist, forward.y * probeDist, forward.z * probeDist);
            tickBlocked = !player.level().noCollision(probe);

            obstacleAheadOnSlope = tickBlocked;
        } else {
            double actualLen = player.position().subtract(lastPlayerPos).length();
            tickBlocked = actualLen < intentLen * BLOCKED_RATIO;
        }

        if (blockedDispatched) return;

        if (tickBlocked) {
            blockedTicks++;
            if (blockedTicks >= BLOCKED_DROP_TICKS) {
                ModNetworking.toServer(BlockedByObstaclePayload.INSTANCE);
                blockedDispatched = true;
                blockedTicks = 0;
            }
        } else {
            blockedTicks = 0;
        }
    }

    public static boolean isObstacleAheadOnSlope() {
        return obstacleAheadOnSlope;
    }

    private static void resetBlockedDetectionState() {
        lastPlayerPos = null;
        lastIntent = null;
        blockedTicks = 0;
        obstacleAheadOnSlope = false;
    }
}
