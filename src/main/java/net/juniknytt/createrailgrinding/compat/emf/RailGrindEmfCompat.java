package net.juniknytt.createrailgrinding.compat.emf;

import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.minecraft.world.entity.player.Player;
import traben.entity_model_features.EMFAnimationApi;
import traben.entity_model_features.utils.EMFEntity;

public final class RailGrindEmfCompat {

    private RailGrindEmfCompat() {}

    public static void init() {
        try {
            EMFAnimationApi.registerPauseCondition(RailGrindEmfCompat::isRailGrinding);
            EMFAnimationApi.registerVanillaModelCondition(RailGrindEmfCompat::isRailGrinding);
        } catch (Exception e) {
            throw new RuntimeException("CreateRailGrinding: failed to register EMF compat conditions", e);
        }
    }

    private static boolean isRailGrinding(EMFEntity emfEntity) {
        return emfEntity instanceof Player player && BalancingPoseTracker.isBalancing(player);
    }
}
