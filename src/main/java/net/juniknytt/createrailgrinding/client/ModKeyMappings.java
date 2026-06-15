package net.juniknytt.createrailgrinding.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = RailGrind.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ModKeyMappings {

    public static final String CATEGORY = "key.categories." + RailGrind.MODID;

    public static final KeyMapping CATCH = new KeyMapping(
            "key." + RailGrind.MODID + ".catch",
            KeyConflictContext.IN_GAME,
            KeyModifier.SHIFT,
            InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_SPACE),
            CATEGORY
    ) {
        @Override
        public boolean isActiveAndMatches(InputConstants.Key keyCode) {
            return false;
        }
    };

    public static final KeyMapping GRIND_JUMP = new KeyMapping(
            "key." + RailGrind.MODID + ".grind_jump",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_SPACE),
            CATEGORY
    );

    public static final KeyMapping GRIND_CROUCH = new KeyMapping(
            "key." + RailGrind.MODID + ".grind_crouch",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_LEFT_SHIFT),
            CATEGORY
    );

    private ModKeyMappings() {}

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(CATCH);
        event.register(GRIND_JUMP);
        event.register(GRIND_CROUCH);
    }
}
