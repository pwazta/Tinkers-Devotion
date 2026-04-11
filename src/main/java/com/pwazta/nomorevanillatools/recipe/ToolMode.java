package com.pwazta.nomorevanillatools.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pwazta.nomorevanillatools.Config;
import com.pwazta.nomorevanillatools.config.MaterialMappingConfig;
import com.pwazta.nomorevanillatools.config.ToolExclusionConfig;
import com.pwazta.nomorevanillatools.loot.VanillaItemMappings;
import com.pwazta.nomorevanillatools.util.TcItemRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.registries.ForgeRegistries;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.tools.definition.ToolDefinition;
import slimeknights.tconstruct.library.tools.definition.module.ToolHooks;
import slimeknights.tconstruct.library.tools.item.IModifiable;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public record ToolMode(String tier, String toolType) implements IngredientMode {

    /** Basic material for non-head display parts. Parsed once, reused across all display builds. */
    private static final MaterialVariantId BASIC_VARIANT = MaterialVariantId.parse("tconstruct:wood");

    @Override
    public String modeName() {
        return "tool_action";
    }

    /** Checks head material (index 0) matches config tier, optional all-parts % threshold, then validates ToolAction + exclusions. */
    @Override
    public boolean test(ItemStack stack) {
        CompoundTag nbt = stack.getTag();
        if (nbt == null || !nbt.contains("tic_materials", Tag.TAG_LIST)) return false;

        ListTag materialsList = nbt.getList("tic_materials", Tag.TAG_STRING);
        if (materialsList.isEmpty()) return false;

        Set<String> allowedMaterials = MaterialMappingConfig.getMaterialsForTier(tier);
        if (allowedMaterials == null || allowedMaterials.isEmpty()) return false;

        // HEAD material (index 0) MUST match
        if (!allowedMaterials.contains(materialsList.getString(0))) return false;

        // Optional: check percentage of all parts (including head)
        if (Config.requireAllPartsMatch && materialsList.size() > 1) {
            int matchingCount = 1; // head already verified
            for (int i = 1; i < materialsList.size(); i++) {
                if (allowedMaterials.contains(materialsList.getString(i))) matchingCount++;
            }
            if ((double) matchingCount / materialsList.size() < Config.allPartsThreshold) return false;
        }

        return matchesToolType(stack);
    }

    /** Verifies item supports the required ToolAction via ToolDefinition hook, then checks exclusion list. */
    private boolean matchesToolType(ItemStack stack) {
        if (toolType.isEmpty()) return true;
        ToolAction requiredAction = VanillaItemMappings.getToolAction(toolType);
        if (requiredAction == null) return true;

        if (!(stack.getItem() instanceof IModifiable modifiable)) return false;
        ToolDefinition definition = modifiable.getToolDefinition();
        if (!definition.isDataLoaded()) return false;

        boolean supportsAction = definition.getData().getHook(ToolHooks.TOOL_ACTION).canPerformAction(ToolStack.from(stack), requiredAction);
        if (!supportsAction) return false;

        ResourceLocation toolId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return toolId == null || !ToolExclusionConfig.isExcluded(toolType, toolId.toString());
    }

    /** Builds JEI display stacks with canonical head material + wooden other parts. Config-aware: respects requireAllPartsMatch threshold. */
    @Override
    public ItemStack[] computeDisplayItems() {
        ToolAction action = VanillaItemMappings.getToolAction(toolType);
        if (action == null) return new ItemStack[0];

        String canonicalId = MaterialMappingConfig.getCanonicalToolMaterial(tier);
        if (canonicalId == null) return new ItemStack[0];
        MaterialVariantId canonical = MaterialVariantId.tryParse(canonicalId);
        if (canonical == null) return new ItemStack[0];

        List<IModifiable> eligible = TcItemRegistry.getEligibleTools(action, toolType);
        return IngredientMode.buildMixedDisplayItems(
            eligible,
            (item, stats) -> {
                int tierParts = Config.requireAllPartsMatch
                    ? Math.max(1, (int) Math.ceil(Config.allPartsThreshold * stats.size()))
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
        json.addProperty("type", "nomorevanillatools:tinker_material");
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
