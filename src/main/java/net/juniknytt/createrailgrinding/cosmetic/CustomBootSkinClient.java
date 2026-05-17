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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Wires the inventory icon swap for renamed rail-grind boots. Implementation note:
 * an earlier attempt overrode Create's model JSONs at {@code assets/create/models/item/...}
 * inside our own jar, expecting NeoForge's mod resource pack to load ours after Create's.
 * That stopped working in practice — Create's JSON kept winning even though we depend on
 * Create — so we now do the swap programmatically against the model registry.
 *
 * Pipeline:
 *   1. {@link ModelEvent.RegisterAdditional} side-loads our {@code createrailgrinding:item/custom_diving_boots}
 *      model under the {@code standalone} variant so it gets baked even though no item
 *      registration references it.
 *   2. {@link ModelEvent.ModifyBakingResult} replaces the {@code #inventory} BakedModel
 *      for EVERY foot-armor item (every {@link ArmorItem} of type {@link ArmorItem.Type#BOOTS})
 *      with a {@link DelegatingBakedModel} whose sole difference from the original is its
 *      {@link BakedModel#getOverrides()} — the anonymous {@link ItemOverrides} there checks
 *      {@link CustomBootSkin#matches(ItemStack)} and returns either the custom model or the
 *      original's own override resolution. Installing on every boot is unconditional; the
 *      delegator is a no-op pass-through for stacks that fail {@code matches()}, so iron /
 *      diamond / modded boots show their original icon unless they're enchanted with Rail
 *      Rider AND renamed to one of {@link CustomBootSkin#NAMES}. Diving boots covered too
 *      via Routine A (no enchantment required).
 *
 * The armor-texture mixin lives separately in
 * {@code mixin/client/HumanoidArmorLayerMixin} and uses the same matcher.
 */
@EventBusSubscriber(modid = RailGrind.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CustomBootSkinClient {
    private CustomBootSkinClient() {}

    private static final ModelResourceLocation CUSTOM_MODEL = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "item/custom_diving_boots"));

    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        event.register(CUSTOM_MODEL);
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
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
            ModelResourceLocation modelKey = ModelResourceLocation.inventory(itemId);
            BakedModel original = models.get(modelKey);
            if (original == null) continue;
            models.put(modelKey, new DelegatingBakedModel(original, custom));
        }
    }

    /**
     * Pass-through wrapper. We only care about overriding {@link #getOverrides()}; every other
     * call routes to the original Create-baked model so quads, transforms, particle icon, and
     * GUI flags stay identical when no skin is active.
     */
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
