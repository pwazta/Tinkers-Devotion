package com.pwazta.nomorevanillatools.recipe;

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
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import slimeknights.tconstruct.library.materials.MaterialRegistry;
import slimeknights.tconstruct.library.materials.definition.IMaterial;
import slimeknights.tconstruct.library.materials.definition.MaterialId;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.tools.item.IModifiable;
import slimeknights.tconstruct.library.tools.item.armor.ModifiableArmorItem;

import java.util.List;

public record ArmorMode(String slot, String armorSet, int minTier, int maxTier) implements IngredientMode {

    @Override
    public String modeName() {
        return "armor_slot";
    }

    /** Checks plating material (index 0) IMaterial.getTier() is within [minTier, maxTier], then validates armor slot + set prefix + exclusions. */
    @Override
    public boolean test(ItemStack stack) {
        CompoundTag nbt = stack.getTag();
        if (nbt == null || !nbt.contains("tic_materials", Tag.TAG_LIST)) return false;

        ListTag materialsList = nbt.getList("tic_materials", Tag.TAG_STRING);
        if (materialsList.isEmpty()) return false;

        // Resolve plating material (index 0) via TC registry and check IMaterial.getTier()
        String platingId = materialsList.getString(0);
        MaterialId materialId = MaterialId.tryParse(platingId);
        if (materialId == null) return false;

        IMaterial material = MaterialRegistry.getInstance().getMaterial(materialId);
        if (material == IMaterial.UNKNOWN) return false;

        int tier = material.getTier();
        if (tier < minTier || tier > maxTier) return false;

        return matchesArmorSlot(stack);
    }

    /** Verifies item is ModifiableArmorItem with correct ArmorItem.Type, matching set prefix, and not excluded. */
    private boolean matchesArmorSlot(ItemStack stack) {
        if (!(stack.getItem() instanceof ModifiableArmorItem armorItem)) return false;
        ArmorItem.Type required = VanillaItemMappings.getArmorType(slot);
        if (required == null || armorItem.getType() != required) return false;

        ResourceLocation armorId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (armorId == null) return false;

        // Filter by armor set prefix (e.g., "tconstruct:plate_" or "tinkers_things:laminar_"). Null = any set.
        if (armorSet != null && !armorId.toString().startsWith(armorSet + "_")) return false;

        return !ToolExclusionConfig.isExcluded(slot, armorId.toString());
    }

    /** Builds JEI display stacks from eligible armor for this slot + set, tagged with nmvt_match_mode for tooltip branching. */
    @Override
    public ItemStack[] computeDisplayItems() {
        ArmorItem.Type armorType = VanillaItemMappings.getArmorType(slot);
        if (armorType == null) return new ItemStack[0];

        String canonicalId = MaterialMappingConfig.getCanonicalArmorMaterial(minTier, maxTier);
        if (canonicalId == null) return new ItemStack[0];
        MaterialVariantId platingVariant = MaterialVariantId.tryParse(canonicalId);
        if (platingVariant == null) return new ItemStack[0];

        String displayLabel = (minTier == maxTier)
            ? String.valueOf(minTier)
            : minTier + "-" + maxTier;

        // Null armorSet = recipe mode: show all eligible armor at this tier/slot regardless of set
        List<? extends IModifiable> eligible = armorSet == null
            ? TcItemRegistry.getAllEligibleArmor(armorType, slot)
            : TcItemRegistry.getEligibleArmor(armorType, List.of(armorSet), slot);

        return IngredientMode.buildMixedDisplayItems(
            eligible,
            // Single-element list = use TC's smart per-slot validation: applies plating to the
            // plating slot, default per-slot material to maille/etc. Matches pre-refactor behavior.
            (item, stats) -> List.of(platingVariant),
            (stack, stats) -> {
                CompoundTag tag = stack.getOrCreateTag();
                tag.putString("nmvt_required_tier", displayLabel);
                tag.putString("nmvt_match_mode", "armor_slot");
            }
        );
    }

    @Override
    public String displayCacheKey() {
        return "armor:" + slot + ":" + armorSet + ":" + minTier + "-" + maxTier;
    }

    @Override
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "nomorevanillatools:tinker_material");
        json.addProperty("mode", "armor_slot");
        json.addProperty("tool_type", slot);
        if (armorSet != null) json.addProperty("armor_set", armorSet);
        json.addProperty("min_tier", minTier);
        json.addProperty("max_tier", maxTier);
        return json;
    }

    @Override
    public void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(slot);
        buffer.writeUtf(armorSet != null ? armorSet : "");
        buffer.writeVarInt(minTier);
        buffer.writeVarInt(maxTier);
    }

    static ArmorMode fromJson(JsonObject json) {
        String slot = json.get("tool_type").getAsString();
        String armorSet = json.has("armor_set") ? json.get("armor_set").getAsString() : null;
        int minTier = json.get("min_tier").getAsInt();
        int maxTier = json.get("max_tier").getAsInt();
        return new ArmorMode(slot, armorSet, minTier, maxTier);
    }

    static ArmorMode fromBuffer(FriendlyByteBuf buffer) {
        String slot = buffer.readUtf();
        String raw = buffer.readUtf();
        String armorSet = raw.isEmpty() ? null : raw;
        int minTier = buffer.readVarInt();
        int maxTier = buffer.readVarInt();
        return new ArmorMode(slot, armorSet, minTier, maxTier);
    }
}
