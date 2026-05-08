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
import net.minecraftforge.common.Tags;
import net.minecraftforge.registries.ForgeRegistries;
import slimeknights.tconstruct.library.tools.item.IModifiable;

import java.util.ArrayList;
import java.util.List;

/**
 * Fishing rod ingredient mode. Type-detection by {@link Tags.Items#TOOLS_FISHING_RODS} membership
 * (the Forge convention tag that TC and any compliant addon populate). The {@code instanceof IModifiable}
 * filter excludes vanilla {@code minecraft:fishing_rod}.
 */
public record FishingRodMode(String fishingRodType, List<String> canonicalMaterials) implements IngredientMode {

    @Override
    public String modeName() { return "fishing_rod"; }

    @Override
    public boolean test(ItemStack stack) {
        return matchesFishingRodType(stack) && IngredientMode.testCanonicalTiers(stack, canonicalMaterials);
    }

    private boolean matchesFishingRodType(ItemStack stack) {
        if (!stack.is(Tags.Items.TOOLS_FISHING_RODS)) return false;
        Item item = stack.getItem();
        if (!(item instanceof IModifiable)) return false;

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(item);
        return itemId == null || !ToolExclusionConfig.isExcluded(fishingRodType, itemId.toString());
    }

    @Override
    public ItemStack[] computeDisplayItems() {
        return IngredientMode.buildCanonicalDisplayItems(
            canonicalMaterials,
            TcItemRegistry.getEligibleFishingRods(fishingRodType),
            "fishing_rod"
        );
    }

    @Override
    public String displayCacheKey() { return "fishing_rod:" + fishingRodType; }

    @Override
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "tinkersdevotion:tinker_material");
        json.addProperty("mode", "fishing_rod");
        json.addProperty("fishing_rod_type", fishingRodType);
        JsonArray materials = new JsonArray();
        for (String m : canonicalMaterials) materials.add(m);
        json.add("canonical_materials", materials);
        return json;
    }

    @Override
    public void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(fishingRodType);
        buffer.writeVarInt(canonicalMaterials.size());
        for (String mat : canonicalMaterials) buffer.writeUtf(mat);
    }

    static FishingRodMode fromJson(JsonObject json) {
        String fishingRodType = json.get("fishing_rod_type").getAsString();
        List<String> canonicalMaterials = new ArrayList<>();
        json.getAsJsonArray("canonical_materials").forEach(e -> canonicalMaterials.add(e.getAsString()));
        return new FishingRodMode(fishingRodType, canonicalMaterials);
    }

    static FishingRodMode fromBuffer(FriendlyByteBuf buffer) {
        String fishingRodType = buffer.readUtf();
        int count = buffer.readVarInt();
        List<String> canonicalMaterials = new ArrayList<>(count);
        for (int i = 0; i < count; i++) canonicalMaterials.add(buffer.readUtf());
        return new FishingRodMode(fishingRodType, canonicalMaterials);
    }
}
