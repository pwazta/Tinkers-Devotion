package com.pwazta.nomorevanillatools.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pwazta.nomorevanillatools.config.ToolExclusionConfig;
import com.pwazta.nomorevanillatools.util.TcItemRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableBowItem;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableCrossbowItem;

import java.util.ArrayList;
import java.util.List;

/** Ranged weapon ingredient mode. Type-detection by {@code instanceof} on TC's bow/crossbow subclasses. */
public record RangedMode(String rangedType, List<String> canonicalMaterials) implements IngredientMode {

    @Override
    public String modeName() { return "ranged"; }

    @Override
    public boolean test(ItemStack stack) {
        return matchesRangedType(stack) && IngredientMode.testCanonicalTiers(stack, canonicalMaterials);
    }

    private boolean matchesRangedType(ItemStack stack) {
        boolean matchesType = switch (rangedType.toLowerCase()) {
            case "bow"      -> stack.getItem() instanceof ModifiableBowItem;
            case "crossbow" -> stack.getItem() instanceof ModifiableCrossbowItem;
            default -> false;
        };
        if (!matchesType) return false;

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return itemId == null || !ToolExclusionConfig.isExcluded(rangedType, itemId.toString());
    }

    @Override
    public ItemStack[] computeDisplayItems() {
        return IngredientMode.buildCanonicalDisplayItems(
            canonicalMaterials,
            TcItemRegistry.getEligibleRanged(rangedType),
            "ranged"
        );
    }

    @Override
    public String displayCacheKey() { return "ranged:" + rangedType; }

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
