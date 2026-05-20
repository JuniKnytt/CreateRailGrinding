package net.juniknytt.createrailgrinding.client;

import net.minecraft.world.entity.player.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BalancingPoseTracker {
    private static final Set<UUID> BALANCING = ConcurrentHashMap.newKeySet();

    private BalancingPoseTracker() {}

    public static boolean isBalancing(Player player) {
        return BALANCING.contains(player.getUUID());
    }

    public static void setBalancing(UUID playerId, boolean balancing) {
        if (balancing) BALANCING.add(playerId);
        else BALANCING.remove(playerId);
    }

    public static void clear() {
        BALANCING.clear();
    }
}
