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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import slimeknights.tconstruct.library.materials.MaterialRegistry;
import slimeknights.tconstruct.library.materials.definition.IMaterial;
import slimeknights.tconstruct.library.materials.definition.MaterialId;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.materials.stats.MaterialStatsId;
import slimeknights.tconstruct.library.tools.definition.ToolDefinition;
import slimeknights.tconstruct.library.tools.definition.module.material.ToolMaterialHook;
import slimeknights.tconstruct.library.tools.item.IModifiable;

import java.util.ArrayList;
import java.util.List;

/** Shield ingredient mode. Uses shield_core stat type for detection (shields are ModifiableItem, not a distinct subclass). */
public record ShieldMode(String shieldType, List<String> canonicalMaterials) implements IngredientMode {

    private static final MaterialStatsId SHIELD_CORE_STAT = new MaterialStatsId("tconstruct", "shield_core");

    @Override
    public String modeName() {
        return "shield";
    }

    @Override
    public boolean test(ItemStack stack) {
        if (canonicalMaterials.isEmpty()) return false;

        if (!matchesShieldType(stack)) return false;

        CompoundTag nbt = stack.getTag();
        if (nbt == null || !nbt.contains("tic_materials", Tag.TAG_LIST)) return false;

        ListTag materialsList = nbt.getList("tic_materials", Tag.TAG_STRING);
        if (materialsList.isEmpty()) return false;

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

    /** Checks item has shield_core stat type and is not excluded. */
    private boolean matchesShieldType(ItemStack stack) {
        Item item = stack.getItem();
        if (!(item instanceof IModifiable modifiable)) return false;

        ToolDefinition def = modifiable.getToolDefinition();
        if (!def.isDataLoaded()) return false;

        if (!ToolMaterialHook.stats(def).contains(SHIELD_CORE_STAT)) return false;

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(item);
        return itemId == null || !ToolExclusionConfig.isExcluded(shieldType, itemId.toString());
    }

    private static int resolveCanonicalTier(String canonicalId) {
        MaterialId matId = MaterialId.tryParse(canonicalId);
        if (matId == null) return -1;
        IMaterial material = MaterialRegistry.getInstance().getMaterial(matId);
        return material == IMaterial.UNKNOWN ? -1 : material.getTier();
    }

    @Override
    public ItemStack[] computeDisplayItems() {
        if (canonicalMaterials.isEmpty()) return new ItemStack[0];

        List<MaterialVariantId> partMaterials = new ArrayList<>(canonicalMaterials.size());
        for (String canonicalId : canonicalMaterials) {
            MaterialVariantId variant = MaterialVariantId.tryParse(canonicalId);
            if (variant == null) return new ItemStack[0];
            partMaterials.add(variant);
        }

        boolean uniform = canonicalMaterials.stream().allMatch(canonicalMaterials.get(0)::equals);
        String firstTierLabel = String.valueOf(resolveCanonicalTier(canonicalMaterials.get(0)));

        List<IModifiable> eligible = TcItemRegistry.getEligibleShields(shieldType);

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
                tag.putString("nmvt_match_mode", "shield");
                tag.putString("nmvt_required_tier", firstTierLabel);
                if (!uniform) {
                    tag.putString("nmvt_part_details", formatPartDetails(canonicalMaterials, stats));
                }
            }
        );
    }

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
        return "shield:" + shieldType;
    }

    @Override
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "nomorevanillatools:tinker_material");
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
