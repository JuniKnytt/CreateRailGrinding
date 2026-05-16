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

/**
 * Reflection-only bridge to Sable (Create Aeronautics' moving sub-level engine). This class
 * imports NO {@code dev.ryanhcode.sable.*} types — every Sable interaction goes through
 * {@link Class#forName} + {@link Method#invoke} on string-keyed lookups, with handles cached
 * by {@link #ensureReady()}.
 *
 * <p><b>Why reflection:</b> Sable isn't a Gradle dependency, so direct imports won't compile.
 * <b>Class-loading discipline:</b> callers must gate every entry point behind
 * {@link Mods#SABLE}{@code .isLoaded()} (via {@link Mods#runIfInstalled} / {@link Mods#executeIfInstalled}).
 * Reflection failures degrade to safe defaults (null / empty / pass-through), so a Sable API
 * change can quietly disable compat rather than crash rail-grinding.
 */
public final class SableSubLevels {
    // Reflection-cache fields. Volatile only for cross-thread late-publication safety; the
    // Minecraft thread model has the rail handler on the server thread only, so contention
    // is theoretical. All fields are set together inside the synchronized ensureReady().
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

    /**
     * Reflective-call helper. Runs {@link #ensureReady()} then invokes {@code call}; on any
     * {@link ReflectiveOperationException} returns {@code fallback}. Used to keep every public
     * method's body a one-liner with a single point that decides the failure default.
     */
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

    /**
     * Opaque wrapper around a Sable {@code SubLevel}. Held as {@link Object} so any class
     * with a {@code SubLevelHandle} field stays loadable when Sable is absent — every method
     * routes through cached reflection rather than direct Sable-typed access.
     *
     * <p>Equality is identity on the wrapped object (record default), which is what callers
     * want for "is this still the same sublevel?" checks.
     */
    public record SubLevelHandle(Object subLevel) {

        /** The sublevel's internal {@code Level} — where rail blocks placed in this sublevel actually live. Null on lookup failure (consumer treats as "drop the grind"). */
        @Nullable
        public Level getLevel() {
            return safely(null, () -> (Level) mSubLevelGetLevel.invoke(subLevel));
        }

        /** True once Sable has marked this sublevel for removal. Defaults to true on failure (safer for the "keep grinding?" decision). */
        public boolean isRemoved() {
            return safely(Boolean.TRUE, () -> (Boolean) mSubLevelIsRemoved.invoke(subLevel));
        }

        /** Local → world via the sublevel's <i>logical</i> (current-tick) pose. */
        public Vec3 toWorld(Vec3 local) {
            return safely(local, () -> (Vec3) mPoseTransformPosition.invoke(mSubLevelLogicalPose.invoke(subLevel), local));
        }

        /** World → local via the sublevel's logical pose. */
        public Vec3 toLocal(Vec3 world) {
            return safely(world, () -> (Vec3) mPoseTransformPositionInverse.invoke(mSubLevelLogicalPose.invoke(subLevel), world));
        }

        /**
         * Rotate a local-space unit vector (rail tangent) into world space. Sable's
         * {@code transformNormal} applies the sublevel's scale, so a unit local input can
         * come out non-unit on a scaled sublevel — we renormalize unconditionally because
         * the grind code expects a unit tangent.
         */
        public Vec3 rotateNormalToWorld(Vec3 localUnit) {
            return safely(localUnit, () -> {
                Vec3 v = (Vec3) mPoseTransformNormal.invoke(mSubLevelLogicalPose.invoke(subLevel), localUnit);
                double lenSq = v.lengthSqr();
                if (lenSq < 1e-18) return v;
                double inv = 1.0 / Math.sqrt(lenSq);
                return new Vec3(v.x * inv, v.y * inv, v.z * inv);
            });
        }

        /**
         * Per-tick world-space velocity of the point on the sublevel currently rendered at
         * {@code worldPos}. Computed as the delta between {@code logicalPose · localPos} and
         * {@code lastPose · localPos}, where {@code localPos = logicalPose⁻¹(worldPos)} —
         * i.e. find which sublevel-local point sits at {@code worldPos} now, then ask where
         * that same local point was last tick. The delta is the right quantity to add to a
         * dismount launch so jumping off a moving sublevel inherits its momentum.
         */
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

    /**
     * Sublevels whose world-space AABB intersects a {@code radius}-padded cube around
     * {@code origin}. Empty list on reflection failure so callers iterate unconditionally.
     * Caller is expected to have gated on {@link Mods#SABLE}{@code .isLoaded()}.
     */
    public static List<SubLevelHandle> sublevelsNear(Level level, Vec3 origin, double radius) {
        return safely(Collections.emptyList(), () -> {
            AABB query = new AABB(
                    origin.x - radius, origin.y - radius, origin.z - radius,
                    origin.x + radius, origin.y + radius, origin.z + radius);
            Object bounds = ctorBoundingBox3dFromAabb.newInstance(query);
            Iterable<?> iter = (Iterable<?>) mGetAllIntersecting.invoke(companionInstance, level, bounds);
            List<SubLevelHandle> out = new ArrayList<>();
            for (Object sla : iter) {
                // getAllIntersecting returns SubLevelAccess; we need the concrete SubLevel
                // for getLevel(). instanceof via the cached class handle filters anything
                // else the implementation might return.
                if (classSubLevel.isInstance(sla)) {
                    out.add(new SubLevelHandle(sla));
                }
            }
            return out;
        });
    }
}
