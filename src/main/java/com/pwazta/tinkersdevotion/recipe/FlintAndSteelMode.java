package com.pwazta.tinkersdevotion.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pwazta.tinkersdevotion.config.ToolExclusionConfig;
import com.pwazta.tinkersdevotion.util.TcItemRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import slimeknights.tconstruct.library.tools.item.IModifiable;

import java.util.ArrayList;
import java.util.List;

/**
 * Flint-and-steel ingredient mode — matches by registry-id membership in {@code tcItemIds}.
 * No per-part tier check (the TC items in this category have no material composition).
 */
public record FlintAndSteelMode(String flintAndSteelType, List<String> tcItemIds) implements IngredientMode {

    @Override
    public String modeName() { return "flint_and_steel"; }

    @Override
    public boolean test(ItemStack stack) {
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) return false;
        String idStr = itemId.toString();
        if (!tcItemIds.contains(idStr)) return false;
        return !ToolExclusionConfig.isExcluded(flintAndSteelType, idStr);
    }

    @Override
    public ItemStack[] computeDisplayItems() {
        List<IModifiable> eligible = TcItemRegistry.getEligibleFlintAndSteel(flintAndSteelType, tcItemIds);
        List<ItemStack> result = new ArrayList<>(eligible.size());
        for (IModifiable item : eligible) {
            ItemStack stack = new ItemStack((Item) item);
            CompoundTag tag = stack.getOrCreateTag();
            tag.putString("nmvt_match_mode", "flint_and_steel");
            result.add(stack);
        }
        return result.toArray(new ItemStack[0]);
    }

    @Override
    public String displayCacheKey() { return "flint_and_steel:" + flintAndSteelType; }

    @Override
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "tinkersdevotion:tinker_material");
        json.addProperty("mode", "flint_and_steel");
        json.addProperty("flint_and_steel_type", flintAndSteelType);
        JsonArray ids = new JsonArray();
        for (String id : tcItemIds) ids.add(id);
        json.add("tc_item_ids", ids);
        return json;
    }

    @Override
    public void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(flintAndSteelType);
        buffer.writeVarInt(tcItemIds.size());
        for (String id : tcItemIds) buffer.writeUtf(id);
    }

    static FlintAndSteelMode fromJson(JsonObject json) {
        String type = json.get("flint_and_steel_type").getAsString();
        List<String> ids = new ArrayList<>();
        json.getAsJsonArray("tc_item_ids").forEach(e -> ids.add(e.getAsString()));
        return new FlintAndSteelMode(type, ids);
    }

    static FlintAndSteelMode fromBuffer(FriendlyByteBuf buffer) {
        String type = buffer.readUtf();
        int count = buffer.readVarInt();
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) ids.add(buffer.readUtf());
        return new FlintAndSteelMode(type, ids);
    }
}
