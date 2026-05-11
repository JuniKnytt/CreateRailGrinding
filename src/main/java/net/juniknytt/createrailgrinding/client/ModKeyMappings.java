package net.juniknytt.createrailgrinding.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

@EventBusSubscriber(modid = RailGrind.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ModKeyMappings {

    public static final String CATEGORY = "key.categories." + RailGrind.MODID;

    public static final KeyMapping RAIL_MOUNT_OVERRIDE = new KeyMapping(
            "key." + RailGrind.MODID + ".rail_mount_override",
            KeyConflictContext.IN_GAME,
            InputConstants.UNKNOWN,
            CATEGORY
    );

    public static final KeyMapping GRIND_CROUCH_ACCELERATE_OVERRIDE = new KeyMapping(
            "key." + RailGrind.MODID + ".grind_crouch_accelerate_override",
            KeyConflictContext.IN_GAME,
            InputConstants.UNKNOWN,
            CATEGORY
    );

    private ModKeyMappings() {}

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(RAIL_MOUNT_OVERRIDE);
        event.register(GRIND_CROUCH_ACCELERATE_OVERRIDE);
    }

    public static boolean isMountOverrideBound() {
        return !RAIL_MOUNT_OVERRIDE.isUnbound();
    }

    public static boolean isAccelOverrideBound() {
        return !GRIND_CROUCH_ACCELERATE_OVERRIDE.isUnbound();
    }
}
