package net.juniknytt.createrailgrinding.ponder;

import com.simibubi.create.content.trains.track.TrackMaterial;

import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

public class RailGrindPonderPlugin implements PonderPlugin {

    @Override
    public String getModId() {
        return RailGrind.MODID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        // Mirrors Create's own train-track ponder pattern: TrackMaterial.allBlocks() lists every
        // registered Train Track variant (Andesite + any modded materials), so the scene attaches
        // to all of them in one shot.
        ResourceLocation[] trackIds = TrackMaterial.allBlocks().stream()
                .map(supplier -> BuiltInRegistries.BLOCK.getKey(supplier.get()))
                .toArray(ResourceLocation[]::new);

        helper.forComponents(trackIds)
                .addStoryBoard("rail_grinding/intro", RailGrindScenes::intro);
    }
}
