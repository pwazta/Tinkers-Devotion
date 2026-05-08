package com.pwazta.tinkersdevotion.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pwazta.tinkersdevotion.Config;
import com.pwazta.tinkersdevotion.config.TiersToTcMaterials;
import com.pwazta.tinkersdevotion.config.ToolExclusionConfig;
import com.pwazta.tinkersdevotion.util.TcItemRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.tools.item.IModifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record ToolMode(String tier, String toolType) implements IngredientMode {

    /** Basic material for non-head display parts. Parsed once, reused across all display builds. */
    private static final MaterialVariantId BASIC_VARIANT = MaterialVariantId.parse("tconstruct:wood");

    @Override
    public String modeName() {
        return "tool_action";
    }

    /** Checks head material (index 0) matches the required tier via live TC registry, optional all-parts % threshold, then verifies tool-type tag membership + exclusions. */
    @Override
    public boolean test(ItemStack stack) {
        CompoundTag nbt = stack.getTag();
        if (nbt == null || !nbt.contains("tic_materials", Tag.TAG_LIST)) return false;

        ListTag materialsList = nbt.getList("tic_materials", Tag.TAG_STRING);
        if (materialsList.isEmpty()) return false;

        // Normalize once — cache values are lowercase, recipe JSON may not be.
        String requiredTier = tier.toLowerCase(Locale.ROOT);

        // HEAD material (index 0) MUST match the required tier (live lookup, no config)
        if (!requiredTier.equals(TiersToTcMaterials.getToolTierName(materialsList.getString(0)))) return false;

        // Optional: require a percentage of parts (including head) to match the tier
        if (Config.partsMatchThreshold > 0.0 && materialsList.size() > 1) {
            int matchingCount = 1; // head already verified above
            for (int i = 1; i < materialsList.size(); i++) {
                if (requiredTier.equals(TiersToTcMaterials.getToolTierName(materialsList.getString(i)))) matchingCount++;
            }
            if ((double) matchingCount / materialsList.size() < Config.partsMatchThreshold) return false;
        }

        return matchesToolType(stack);
    }

    /** Verifies item is in the per-action item tag (TC populates these from each tool's static ToolActionsModule), then checks exclusion list. */
    private boolean matchesToolType(ItemStack stack) {
        if (toolType.isEmpty()) return true;
        TagKey<Item> tag = TcItemRegistry.getToolTag(toolType);
        if (tag == null) return true;
        if (!(stack.getItem() instanceof IModifiable)) return false;
        if (!stack.is(tag)) return false;

        ResourceLocation toolId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return toolId == null || !ToolExclusionConfig.isExcluded(toolType, toolId.toString());
    }

    /** Builds JEI display stacks with canonical head material + wooden other parts. Respects partsMatchThreshold. */
    @Override
    public ItemStack[] computeDisplayItems() {
        String canonicalId = TiersToTcMaterials.getCanonicalToolMaterial(tier);
        if (canonicalId == null) return new ItemStack[0];
        MaterialVariantId canonical = MaterialVariantId.tryParse(canonicalId);
        if (canonical == null) return new ItemStack[0];

        List<IModifiable> eligible = TcItemRegistry.getEligibleTools(toolType);
        return IngredientMode.buildMixedDisplayItems(
            eligible,
            (item, stats) -> {
                int tierParts = Config.partsMatchThreshold > 0.0
                    ? Math.max(1, (int) Math.ceil(Config.partsMatchThreshold * stats.size()))
                    : 1;
                List<MaterialVariantId> mats = new ArrayList<>(stats.size());
                for (int i = 0; i < stats.size(); i++)
                    mats.add(i < tierParts ? canonical : BASIC_VARIANT);
                return mats;
            },
            (stack, stats) -> stack.getOrCreateTag().putString("nmvt_required_tier", tier)
        );
    }

    @Override
    public String displayCacheKey() {
        return toolType + ":" + tier;
    }

    @Override
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "tinkersdevotion:tinker_material");
        json.addProperty("mode", "tool_action");
        json.addProperty("tool_type", toolType);
        json.addProperty("tier", tier);
        return json;
    }

    @Override
    public void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(toolType);
        buffer.writeUtf(tier);
    }

    static ToolMode fromJson(JsonObject json) {
        String toolType = json.get("tool_type").getAsString();
        String tier = json.get("tier").getAsString();
        return new ToolMode(tier, toolType);
    }

    static ToolMode fromBuffer(FriendlyByteBuf buffer) {
        String toolType = buffer.readUtf();
        String tier = buffer.readUtf();
        return new ToolMode(tier, toolType);
    }
}
