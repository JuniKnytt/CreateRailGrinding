package net.juniknytt.createrailgrinding.cosmetic;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = RailGrind.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CustomBootSkinClient {
    private CustomBootSkinClient() {}

    private static final ModelResourceLocation CUSTOM_MODEL = new ModelResourceLocation(
            new ResourceLocation(RailGrind.MODID, "custom_diving_boots"), "inventory");

    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        event.register(CUSTOM_MODEL);
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ResourceLocation, BakedModel> models = event.getModels();
        BakedModel custom = models.get(CUSTOM_MODEL);
        if (custom == null) {
            RailGrind.LOGGER.warn("CustomBootSkin: standalone model not baked, skin swap disabled");
            return;
        }
        for (Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof ArmorItem armor)) continue;
            if (armor.getType() != ArmorItem.Type.BOOTS) continue;
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            if (itemId == null) continue;
            ModelResourceLocation modelKey = new ModelResourceLocation(itemId, "inventory");
            BakedModel original = models.get(modelKey);
            if (original == null) continue;
            models.put(modelKey, new DelegatingBakedModel(original, custom));
        }
    }

    private static final class DelegatingBakedModel implements BakedModel {
        private final BakedModel original;
        private final BakedModel customModel;
        private final ItemOverrides overrides;

        DelegatingBakedModel(BakedModel original, BakedModel customModel) {
            this.original = original;
            this.customModel = customModel;
            this.overrides = new ItemOverrides() {
                @Override
                public BakedModel resolve(BakedModel model, ItemStack stack, @Nullable ClientLevel level,
                                          @Nullable LivingEntity entity, int seed) {
                    if (CustomBootSkin.matches(stack)) {
                        return DelegatingBakedModel.this.customModel;
                    }
                    BakedModel orig = DelegatingBakedModel.this.original;
                    return orig.getOverrides().resolve(orig, stack, level, entity, seed);
                }
            };
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random) {
            return original.getQuads(state, direction, random);
        }

        @Override
        public boolean useAmbientOcclusion() { return original.useAmbientOcclusion(); }

        @Override
        public boolean isGui3d() { return original.isGui3d(); }

        @Override
        public boolean usesBlockLight() { return original.usesBlockLight(); }

        @Override
        public boolean isCustomRenderer() { return original.isCustomRenderer(); }

        @Override
        public TextureAtlasSprite getParticleIcon() { return original.getParticleIcon(); }

        @Override
        public ItemTransforms getTransforms() { return original.getTransforms(); }

        @Override
        public ItemOverrides getOverrides() { return overrides; }
    }
}
