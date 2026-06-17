package net.juniknytt.createrailgrinding.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = RailGrind.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
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
