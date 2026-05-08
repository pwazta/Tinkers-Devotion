package com.pwazta.tinkersdevotion.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pwazta.tinkersdevotion.config.ToolExclusionConfig;
import com.pwazta.tinkersdevotion.util.TcItemRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import slimeknights.tconstruct.library.materials.stats.MaterialStatsId;
import slimeknights.tconstruct.library.tools.definition.ToolDefinition;
import slimeknights.tconstruct.library.tools.definition.module.material.ToolMaterialHook;
import slimeknights.tconstruct.library.tools.item.IModifiable;
import slimeknights.tconstruct.library.tools.item.armor.ModifiableArmorItem;

import java.util.ArrayList;
import java.util.List;

/** Shield ingredient mode. Type-detection by {@code shield_core} stat-type presence (TC shields are plain ModifiableItem). */
public record ShieldMode(String shieldType, List<String> canonicalMaterials) implements IngredientMode {

    private static final MaterialStatsId SHIELD_CORE_STAT = new MaterialStatsId("tconstruct", "shield_core");

    @Override
    public String modeName() { return "shield"; }

    @Override
    public boolean test(ItemStack stack) {
        return matchesShieldType(stack) && IngredientMode.testCanonicalTiers(stack, canonicalMaterials);
    }

    private boolean matchesShieldType(ItemStack stack) {
        Item item = stack.getItem();
        if (!(item instanceof IModifiable modifiable)) return false;
        if (item instanceof ModifiableArmorItem) return false;

        ToolDefinition def = modifiable.getToolDefinition();
        if (!def.isDataLoaded()) return false;
        if (!ToolMaterialHook.stats(def).contains(SHIELD_CORE_STAT)) return false;

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(item);
        return itemId == null || !ToolExclusionConfig.isExcluded(shieldType, itemId.toString());
    }

    @Override
    public ItemStack[] computeDisplayItems() {
        return IngredientMode.buildCanonicalDisplayItems(
            canonicalMaterials,
            TcItemRegistry.getEligibleShields(shieldType),
            "shield"
        );
    }

    @Override
    public String displayCacheKey() { return "shield:" + shieldType; }

    @Override
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "tinkersdevotion:tinker_material");
        json.addProperty("mode", "shield");
        json.addProperty("shield_type", shieldType);
        JsonArray materials = new JsonArray();
        for (String m : canonicalMaterials) materials.add(m);
        json.add("canonical_materials", materials);
        return json;
    }

    @Override
    public void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(shieldType);
        buffer.writeVarInt(canonicalMaterials.size());
        for (String mat : canonicalMaterials) buffer.writeUtf(mat);
    }

    static ShieldMode fromJson(JsonObject json) {
        String shieldType = json.get("shield_type").getAsString();
        List<String> canonicalMaterials = new ArrayList<>();
        json.getAsJsonArray("canonical_materials").forEach(e -> canonicalMaterials.add(e.getAsString()));
        return new ShieldMode(shieldType, canonicalMaterials);
    }

    static ShieldMode fromBuffer(FriendlyByteBuf buffer) {
        String shieldType = buffer.readUtf();
        int count = buffer.readVarInt();
        List<String> canonicalMaterials = new ArrayList<>(count);
        for (int i = 0; i < count; i++) canonicalMaterials.add(buffer.readUtf());
        return new ShieldMode(shieldType, canonicalMaterials);
    }
}
