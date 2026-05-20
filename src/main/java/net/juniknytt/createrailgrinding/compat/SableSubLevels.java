package net.juniknytt.createrailgrinding.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class SableSubLevels {

    private static volatile boolean reflectionReady = false;
    private static Object companionInstance;
    private static Class<?> classSubLevel;
    private static Method mGetAllIntersecting;
    private static Method mSubLevelGetLevel;
    private static Method mSubLevelIsRemoved;
    private static Method mSubLevelLogicalPose;
    private static Method mSubLevelLastPose;
    private static Method mPoseTransformPosition;
    private static Method mPoseTransformPositionInverse;
    private static Method mPoseTransformNormal;
    private static Constructor<?> ctorBoundingBox3dFromAabb;

    private SableSubLevels() {}

    private static synchronized void ensureReady() throws ReflectiveOperationException {
        if (reflectionReady) return;

        Class<?> classSableCompanion = Class.forName("dev.ryanhcode.sable.companion.SableCompanion");
        companionInstance = classSableCompanion.getField("INSTANCE").get(null);

        Class<?> classBoundingBox3dc = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3dc");
        Class<?> classBoundingBox3d = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3d");
        ctorBoundingBox3dFromAabb = classBoundingBox3d.getConstructor(AABB.class);

        mGetAllIntersecting = classSableCompanion.getMethod("getAllIntersecting", Level.class, classBoundingBox3dc);

        classSubLevel = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
        mSubLevelGetLevel = classSubLevel.getMethod("getLevel");
        mSubLevelIsRemoved = classSubLevel.getMethod("isRemoved");
        mSubLevelLogicalPose = classSubLevel.getMethod("logicalPose");
        mSubLevelLastPose = classSubLevel.getMethod("lastPose");

        Class<?> classPose3dc = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
        mPoseTransformPosition = classPose3dc.getMethod("transformPosition", Vec3.class);
        mPoseTransformPositionInverse = classPose3dc.getMethod("transformPositionInverse", Vec3.class);
        mPoseTransformNormal = classPose3dc.getMethod("transformNormal", Vec3.class);

        reflectionReady = true;
    }

    private static <T> T safely(T fallback, ReflectiveCall<T> call) {
        try {
            ensureReady();
            return call.invoke();
        } catch (ReflectiveOperationException e) {
            return fallback;
        }
    }

    @FunctionalInterface
    private interface ReflectiveCall<T> {
        T invoke() throws ReflectiveOperationException;
    }

    public record SubLevelHandle(Object subLevel) {

        @Nullable
        public Level getLevel() {
            return safely(null, () -> (Level) mSubLevelGetLevel.invoke(subLevel));
        }

        public boolean isRemoved() {
            return safely(Boolean.TRUE, () -> (Boolean) mSubLevelIsRemoved.invoke(subLevel));
        }

        public Vec3 toWorld(Vec3 local) {
            return safely(local, () -> (Vec3) mPoseTransformPosition.invoke(mSubLevelLogicalPose.invoke(subLevel), local));
        }

        public Vec3 toLocal(Vec3 world) {
            return safely(world, () -> (Vec3) mPoseTransformPositionInverse.invoke(mSubLevelLogicalPose.invoke(subLevel), world));
        }

        public Vec3 rotateNormalToWorld(Vec3 localUnit) {
            return safely(localUnit, () -> {
                Vec3 v = (Vec3) mPoseTransformNormal.invoke(mSubLevelLogicalPose.invoke(subLevel), localUnit);
                double lenSq = v.lengthSqr();
                if (lenSq < 1e-18) return v;
                double inv = 1.0 / Math.sqrt(lenSq);
                return new Vec3(v.x * inv, v.y * inv, v.z * inv);
            });
        }

        public Vec3 sublevelVelocityAt(Vec3 worldPos) {
            return safely(Vec3.ZERO, () -> {
                Object logicalPose = mSubLevelLogicalPose.invoke(subLevel);
                Object lastPose = mSubLevelLastPose.invoke(subLevel);
                Vec3 localPos = (Vec3) mPoseTransformPositionInverse.invoke(logicalPose, worldPos);
                Vec3 prevWorld = (Vec3) mPoseTransformPosition.invoke(lastPose, localPos);
                return worldPos.subtract(prevWorld);
            });
        }
    }

    public static List<SubLevelHandle> sublevelsNear(Level level, Vec3 origin, double radius) {
        return safely(Collections.emptyList(), () -> {
            AABB query = new AABB(
                    origin.x - radius, origin.y - radius, origin.z - radius,
                    origin.x + radius, origin.y + radius, origin.z + radius);
            Object bounds = ctorBoundingBox3dFromAabb.newInstance(query);
            Iterable<?> iter = (Iterable<?>) mGetAllIntersecting.invoke(companionInstance, level, bounds);
            List<SubLevelHandle> out = new ArrayList<>();
            for (Object sla : iter) {

                if (classSubLevel.isInstance(sla)) {
                    out.add(new SubLevelHandle(sla));
                }
            }
            return out;
        });
    }
}
