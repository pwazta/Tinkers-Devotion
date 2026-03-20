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
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableBowItem;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableCrossbowItem;

import java.util.ArrayList;
import java.util.List;

public record RangedMode(String rangedType, List<String> partTiers) implements IngredientMode {

    @Override
    public String modeName() {
        return "ranged";
    }

    /** Validates ranged type (bow/crossbow) + exclusions, then checks each part's IMaterial.getTier() >= per-part floor from partTiers. */
    @Override
    public boolean test(ItemStack stack) {
        if (partTiers.isEmpty()) return false;

        if (!matchesRangedType(stack)) return false;

        CompoundTag nbt = stack.getTag();
        if (nbt == null || !nbt.contains("tic_materials", Tag.TAG_LIST)) return false;

        ListTag materialsList = nbt.getList("tic_materials", Tag.TAG_STRING);
        if (materialsList.isEmpty()) return false;

        // Per-part tier floor: each part's IMaterial.getTier() >= required floor
        for (int i = 0; i < partTiers.size() && i < materialsList.size(); i++) {
            Integer requiredFloor = VanillaItemMappings.TIER_NAME_TO_INT.get(partTiers.get(i).toLowerCase());
            if (requiredFloor == null) return false;

            MaterialId materialId = MaterialId.tryParse(materialsList.getString(i));
            if (materialId == null) return false;

            IMaterial material = MaterialRegistry.getInstance().getMaterial(materialId);
            if (material == IMaterial.UNKNOWN) return false;

            if (material.getTier() < requiredFloor) return false;
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

    /** Builds JEI display stacks from eligible ranged weapons, tagged with nmvt_match_mode for tooltip branching. */
    @Override
    public ItemStack[] computeDisplayItems() {
        if (partTiers.isEmpty()) return new ItemStack[0];
        String canonicalId = MaterialMappingConfig.getCanonicalToolMaterial(partTiers.get(0));
        String displayLabel = partTiers.get(0);
        ItemStack[] items = IngredientMode.buildDisplayItems(canonicalId, displayLabel,
            TcItemRegistry.getEligibleRanged(rangedType));
        for (ItemStack item : items) {
            item.getOrCreateTag().putString("nmvt_match_mode", "ranged");
        }
        return items;
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
