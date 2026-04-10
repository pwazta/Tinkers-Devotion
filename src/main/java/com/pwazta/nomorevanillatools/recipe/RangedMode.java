package com.pwazta.nomorevanillatools.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import net.minecraftforge.registries.ForgeRegistries;
import slimeknights.tconstruct.library.materials.MaterialRegistry;
import slimeknights.tconstruct.library.materials.definition.IMaterial;
import slimeknights.tconstruct.library.materials.definition.MaterialId;
import slimeknights.tconstruct.library.materials.definition.MaterialVariant;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.materials.stats.MaterialStatsId;
import slimeknights.tconstruct.library.tools.definition.ToolDefinition;
import slimeknights.tconstruct.library.tools.definition.module.material.ToolMaterialHook;
import slimeknights.tconstruct.library.tools.helper.ToolBuildHandler;
import slimeknights.tconstruct.library.tools.item.IModifiable;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableBowItem;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableCrossbowItem;
import slimeknights.tconstruct.library.tools.nbt.MaterialNBT;

import java.util.ArrayList;
import java.util.List;

public record RangedMode(String rangedType, List<String> partTiers) implements IngredientMode {

    @Override
    public String modeName() {
        return "ranged";
    }

    /** Validates ranged type (bow/crossbow) + exclusions, then checks each part's IMaterial.getTier() exactly matches per-part tier from partTiers. */
    @Override
    public boolean test(ItemStack stack) {
        if (partTiers.isEmpty()) return false;

        if (!matchesRangedType(stack)) return false;

        CompoundTag nbt = stack.getTag();
        if (nbt == null || !nbt.contains("tic_materials", Tag.TAG_LIST)) return false;

        ListTag materialsList = nbt.getList("tic_materials", Tag.TAG_STRING);
        if (materialsList.isEmpty()) return false;

        // Per-part exact tier match: each part's IMaterial.getTier() must equal the required tier
        for (int i = 0; i < partTiers.size() && i < materialsList.size(); i++) {
            Integer requiredTier = VanillaItemMappings.TIER_NAME_TO_INT.get(partTiers.get(i).toLowerCase());
            if (requiredTier == null) return false;

            MaterialId materialId = MaterialId.tryParse(materialsList.getString(i));
            if (materialId == null) return false;

            IMaterial material = MaterialRegistry.getInstance().getMaterial(materialId);
            if (material == IMaterial.UNKNOWN) return false;

            if (material.getTier() != requiredTier) return false;
        }

        return true;
    }

    /** Checks item is ModifiableBowItem or ModifiableCrossbowItem matching rangedType, and not excluded. */
    private boolean matchesRangedType(ItemStack stack) {
        if (rangedType.isEmpty()) return false;

        boolean matchesType = switch (rangedType.toLowerCase()) {
            case "bow"      -> stack.getItem() instanceof ModifiableBowItem;
            case "crossbow" -> stack.getItem() instanceof ModifiableCrossbowItem;
            default -> false;
        };
        if (!matchesType) return false;

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return itemId == null || !ToolExclusionConfig.isExcluded(rangedType, itemId.toString());
    }

    /** Builds JEI display stacks with per-part canonical materials. Tags mixed-tier items with formatted part details for tooltip. */
    @Override
    public ItemStack[] computeDisplayItems() {
        if (partTiers.isEmpty()) return new ItemStack[0];

        // Resolve canonical materials per part tier
        List<MaterialVariantId> partMaterials = new ArrayList<>();
        for (String tierName : partTiers) {
            String canonicalId = MaterialMappingConfig.getCanonicalToolMaterial(tierName);
            if (canonicalId == null) return new ItemStack[0];
            MaterialVariantId variant = MaterialVariantId.tryParse(canonicalId);
            if (variant == null) return new ItemStack[0];
            partMaterials.add(variant);
        }

        boolean uniform = partTiers.stream().allMatch(partTiers.get(0)::equals);

        List<IModifiable> eligible = TcItemRegistry.getEligibleRanged(rangedType);
        List<ItemStack> result = new ArrayList<>();
        for (IModifiable item : eligible) {
            ToolDefinition def = item.getToolDefinition();
            if (!def.isDataLoaded()) continue;
            List<MaterialStatsId> statTypes = ToolMaterialHook.stats(def);
            int partCount = statTypes.size();

            MaterialNBT.Builder builder = MaterialNBT.builder();
            for (int i = 0; i < partCount; i++) {
                MaterialVariantId mat = i < partMaterials.size()
                    ? partMaterials.get(i)
                    : partMaterials.get(partMaterials.size() - 1);
                builder.add(mat);
            }

            ItemStack stack = ToolBuildHandler.buildItemFromMaterials(item, builder.build());
            if (stack.isEmpty()) {
                // Fallback: addon weapon with unexpected parts — use single-material display
                stack = ToolBuildHandler.createSingleMaterial(item, MaterialVariant.of(partMaterials.get(0)));
            }
            if (!stack.isEmpty()) {
                stack.getOrCreateTag().putString("nmvt_match_mode", "ranged");
                stack.getOrCreateTag().putString("nmvt_required_tier", partTiers.get(0));
                if (!uniform) {
                    stack.getOrCreateTag().putString("nmvt_part_details",
                        formatPartDetails(partTiers, statTypes));
                }
                result.add(stack);
            }
        }
        return result.toArray(new ItemStack[0]);
    }

    /** Formats per-part tier details for tooltip, e.g. "wooden-tier Limb, iron-tier Grip, wooden-tier Bowstring". */
    private static String formatPartDetails(List<String> tiers, List<MaterialStatsId> statTypes) {
        StringBuilder sb = new StringBuilder();
        int count = Math.min(tiers.size(), statTypes.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append(tiers.get(i)).append("-tier ").append(formatPartName(statTypes.get(i).getPath()));
        }
        return sb.toString();
    }

    /** Capitalizes a stat type path, e.g. "bow_grip" → "Bow Grip". */
    private static String formatPartName(String path) {
        String[] words = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) sb.append(' ');
            if (!words[i].isEmpty()) sb.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
        }
        return sb.toString();
    }

    @Override
    public String displayCacheKey() {
        return "ranged:" + rangedType;
    }

    @Override
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "nomorevanillatools:tinker_material");
        json.addProperty("mode", "ranged");
        json.addProperty("ranged_type", rangedType);
        JsonArray tiers = new JsonArray();
        for (String t : partTiers) tiers.add(t);
        json.add("part_tiers", tiers);
        return json;
    }

    @Override
    public void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(rangedType); // stores rangedType
        buffer.writeVarInt(partTiers.size());
        for (String tier : partTiers) buffer.writeUtf(tier);
    }

    static RangedMode fromJson(JsonObject json) {
        String rangedType = json.get("ranged_type").getAsString();
        List<String> partTiers = new ArrayList<>();
        json.getAsJsonArray("part_tiers").forEach(e -> partTiers.add(e.getAsString()));
        return new RangedMode(rangedType, partTiers);
    }

    static RangedMode fromBuffer(FriendlyByteBuf buffer) {
        String rangedType = buffer.readUtf();
        int count = buffer.readVarInt();
        List<String> partTiers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) partTiers.add(buffer.readUtf());
        return new RangedMode(rangedType, partTiers);
    }
}
