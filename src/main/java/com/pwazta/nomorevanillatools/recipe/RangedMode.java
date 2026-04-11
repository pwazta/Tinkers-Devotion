package com.pwazta.nomorevanillatools.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pwazta.nomorevanillatools.config.ToolExclusionConfig;
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
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.materials.stats.MaterialStatsId;
import slimeknights.tconstruct.library.tools.item.IModifiable;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableBowItem;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableCrossbowItem;

import java.util.ArrayList;
import java.util.List;

/** Ranged weapon ingredient mode. Canonical materials define both display and tier requirements — tiers derived via IMaterial.getTier(). */
public record RangedMode(String rangedType, List<String> canonicalMaterials) implements IngredientMode {

    @Override
    public String modeName() {
        return "ranged";
    }

    /** Validates ranged type + exclusions, then checks each part's IMaterial.getTier() matches the tier derived from the canonical material. */
    @Override
    public boolean test(ItemStack stack) {
        if (canonicalMaterials.isEmpty()) return false;

        if (!matchesRangedType(stack)) return false;

        CompoundTag nbt = stack.getTag();
        if (nbt == null || !nbt.contains("tic_materials", Tag.TAG_LIST)) return false;

        ListTag materialsList = nbt.getList("tic_materials", Tag.TAG_STRING);
        if (materialsList.isEmpty()) return false;

        // Per-part tier match: each part's tier must equal the canonical material's tier
        for (int i = 0; i < canonicalMaterials.size() && i < materialsList.size(); i++) {
            int requiredTier = resolveCanonicalTier(canonicalMaterials.get(i));
            if (requiredTier < 0) return false;

            MaterialId materialId = MaterialId.tryParse(materialsList.getString(i));
            if (materialId == null) return false;

            IMaterial material = MaterialRegistry.getInstance().getMaterial(materialId);
            if (material == IMaterial.UNKNOWN) return false;

            if (material.getTier() != requiredTier) return false;
        }

        return true;
    }

    /** Resolves a canonical material ID to its IMaterial.getTier() int. Returns -1 if unresolvable. */
    private static int resolveCanonicalTier(String canonicalId) {
        MaterialId matId = MaterialId.tryParse(canonicalId);
        if (matId == null) return -1;
        IMaterial material = MaterialRegistry.getInstance().getMaterial(matId);
        return material == IMaterial.UNKNOWN ? -1 : material.getTier();
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

    /** Builds JEI display stacks using canonical materials directly. Tags mixed-tier items with per-part details for tooltip. */
    @Override
    public ItemStack[] computeDisplayItems() {
        if (canonicalMaterials.isEmpty()) return new ItemStack[0];

        // Parse canonical material IDs once
        List<MaterialVariantId> partMaterials = new ArrayList<>(canonicalMaterials.size());
        for (String canonicalId : canonicalMaterials) {
            MaterialVariantId variant = MaterialVariantId.tryParse(canonicalId);
            if (variant == null) return new ItemStack[0];
            partMaterials.add(variant);
        }

        boolean uniform = canonicalMaterials.stream().allMatch(canonicalMaterials.get(0)::equals);
        String firstTierLabel = String.valueOf(resolveCanonicalTier(canonicalMaterials.get(0)));

        List<IModifiable> eligible = TcItemRegistry.getEligibleRanged(rangedType);

        return IngredientMode.buildMixedDisplayItems(
            eligible,
            (item, stats) -> {
                List<MaterialVariantId> mats = new ArrayList<>(stats.size());
                for (int i = 0; i < stats.size(); i++) {
                    int idx = Math.min(i, partMaterials.size() - 1);
                    mats.add(partMaterials.get(idx));
                }
                return mats;
            },
            (stack, stats) -> {
                CompoundTag tag = stack.getOrCreateTag();
                tag.putString("nmvt_match_mode", "ranged");
                tag.putString("nmvt_required_tier", firstTierLabel);
                if (!uniform) {
                    tag.putString("nmvt_part_details", formatPartDetails(canonicalMaterials, stats));
                }
            }
        );
    }

    /** Formats per-part tier details for tooltip, e.g. "tier 0 Limb, tier 2 Grip, tier 0 Bowstring". */
    private static String formatPartDetails(List<String> materials, List<MaterialStatsId> statTypes) {
        StringBuilder sb = new StringBuilder();
        int count = Math.min(materials.size(), statTypes.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            int tier = resolveCanonicalTier(materials.get(i));
            sb.append("tier ").append(tier).append(' ').append(formatPartName(statTypes.get(i).getPath()));
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
        JsonArray materials = new JsonArray();
        for (String m : canonicalMaterials) materials.add(m);
        json.add("canonical_materials", materials);
        return json;
    }

    @Override
    public void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(rangedType);
        buffer.writeVarInt(canonicalMaterials.size());
        for (String mat : canonicalMaterials) buffer.writeUtf(mat);
    }

    static RangedMode fromJson(JsonObject json) {
        String rangedType = json.get("ranged_type").getAsString();
        List<String> canonicalMaterials = new ArrayList<>();
        json.getAsJsonArray("canonical_materials").forEach(e -> canonicalMaterials.add(e.getAsString()));
        return new RangedMode(rangedType, canonicalMaterials);
    }

    static RangedMode fromBuffer(FriendlyByteBuf buffer) {
        String rangedType = buffer.readUtf();
        int count = buffer.readVarInt();
        List<String> canonicalMaterials = new ArrayList<>(count);
        for (int i = 0; i < count; i++) canonicalMaterials.add(buffer.readUtf());
        return new RangedMode(rangedType, canonicalMaterials);
    }
}
