package com.pwazta.nomorevanillatools.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pwazta.nomorevanillatools.Config;
import com.pwazta.nomorevanillatools.config.MaterialMappingConfig;
import com.pwazta.nomorevanillatools.config.ToolExclusionConfig;
import com.pwazta.nomorevanillatools.loot.VanillaItemMappings;
import com.pwazta.nomorevanillatools.util.TcItemRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.common.crafting.AbstractIngredient;
import net.minecraftforge.common.crafting.IIngredientSerializer;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import slimeknights.tconstruct.library.materials.MaterialRegistry;
import slimeknights.tconstruct.library.materials.definition.IMaterial;
import slimeknights.tconstruct.library.materials.definition.MaterialId;
import slimeknights.tconstruct.library.materials.definition.MaterialVariant;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.tools.definition.ToolDefinition;
import slimeknights.tconstruct.library.tools.definition.module.ToolHooks;
import slimeknights.tconstruct.library.tools.helper.ToolBuildHandler;
import slimeknights.tconstruct.library.tools.item.IModifiable;
import slimeknights.tconstruct.library.tools.item.armor.ModifiableArmorItem;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableBowItem;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableCrossbowItem;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Custom ingredient that matches Tinker's Construct tools, armor, or ranged weapons by material tier and type.
 *
 * <p>Three modes:
 * <ul>
 *   <li><b>TOOL_ACTION</b>: matches assembled TC tools by head material's config tier and ToolAction capability.
 *   <li><b>ARMOR_SLOT</b>: matches TC armor by plating material's {@code IMaterial.getTier()} (int 0-4)
 *       within a [minTier, maxTier] range, filtered to a specific armor set (travelers/plate).
 *   <li><b>RANGED</b>: matches TC ranged weapons (bow/crossbow) by per-part {@code IMaterial.getTier()} floor.
 * </ul>
 *
 * <p>For tools, a configurable percentage of other parts can optionally be required to match.
 */
public class TinkerMaterialIngredient extends AbstractIngredient {
    /** Matching mode: tool action, armor slot, or ranged weapon type. */
    public enum MatchMode { TOOL_ACTION, ARMOR_SLOT, RANGED }

    // ── Caches ──────────────────────────────────────────────────────────

    /** Cache of display ItemStacks per type:tier key. Built from TcItemRegistry + canonical material. */
    private static final Map<String, ItemStack[]> DISPLAY_CACHE = new ConcurrentHashMap<>();

    /** Clears display cache. Called when exclusions or material mappings change. */
    public static void clearDisplayCache() {
        DISPLAY_CACHE.clear();
    }

    // ── Instance fields ──────────────────────────────────────────────────

    private final String requiredTier; // e.g., "wooden", "iron" — used by TOOL_ACTION mode
    private final String toolType;     // e.g., "sword", "pickaxe", "helmet", "chestplate"
    private final MatchMode matchMode;

    // Armor-specific fields (ARMOR_SLOT mode only)
    private final @Nullable String armorSet; // e.g., "travelers", "plate"
    private final int minTier;               // IMaterial.getTier() lower bound (inclusive)
    private final int maxTier;               // IMaterial.getTier() upper bound (inclusive)

    // Ranged-specific field (RANGED mode only)
    private final @Nullable List<String> partTiers; // per-part tier floors

    private static final Map<String, Integer> TIER_NAME_TO_INT = Map.of(
        "wooden", 0, "stone", 1, "iron", 2, "golden", 1, "diamond", 3, "netherite", 4
    );

    /** Full constructor. */
    protected TinkerMaterialIngredient(String requiredTier, String toolType, MatchMode matchMode,
                                       @Nullable String armorSet, int minTier, int maxTier,
                                       @Nullable List<String> partTiers) {
        super(Stream.empty());
        this.requiredTier = requiredTier;
        this.toolType = toolType;
        this.matchMode = matchMode;
        this.armorSet = armorSet;
        this.minTier = minTier;
        this.maxTier = maxTier;
        this.partTiers = partTiers;
    }

    /** Tool-mode convenience constructor. */
    protected TinkerMaterialIngredient(String requiredTier, String toolType, MatchMode matchMode) {
        this(requiredTier, toolType, matchMode, null, 0, 0, null);
    }

    /** Ranged-mode convenience constructor. toolType stores rangedType internally. */
    protected TinkerMaterialIngredient(String rangedType, MatchMode matchMode, List<String> partTiers) {
        this(null, rangedType, matchMode, null, 0, 0, partTiers);
    }

    // ── test() — main matching logic ─────────────────────────────────────

    @Override
    public boolean test(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return switch (matchMode) {
            case TOOL_ACTION -> testTool(stack);
            case ARMOR_SLOT  -> testArmor(stack);
            case RANGED      -> testRanged(stack);
        };
    }

    private boolean testTool(ItemStack stack) {
        CompoundTag nbt = stack.getTag();
        if (nbt == null || !nbt.contains("tic_materials", Tag.TAG_LIST)) return false;

        ListTag materialsList = nbt.getList("tic_materials", Tag.TAG_STRING);
        if (materialsList.isEmpty()) return false;

        Set<String> allowedMaterials = MaterialMappingConfig.getMaterialsForTier(requiredTier);
        if (allowedMaterials == null || allowedMaterials.isEmpty()) return false;

        // HEAD material (index 0) MUST match
        if (!allowedMaterials.contains(materialsList.getString(0))) return false;

        // Optional: check percentage of total parts
        if (Config.requireOtherPartsMatch && materialsList.size() > 1) {
            int matchingCount = 1; // head already verified
            for (int i = 1; i < materialsList.size(); i++) {
                if (allowedMaterials.contains(materialsList.getString(i))) matchingCount++;
            }
            if ((double) matchingCount / materialsList.size() < Config.otherPartsThreshold) return false;
        }

        return matchesToolType(stack);
    }

    private boolean testArmor(ItemStack stack) {
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

    // ── Tool type matching ───────────────────────────────────────────────

    private boolean matchesToolType(ItemStack stack) {
        if (toolType == null || toolType.isEmpty()) return true;
        ToolAction requiredAction = VanillaItemMappings.getToolAction(toolType);
        if (requiredAction == null) return true;

        if (!(stack.getItem() instanceof IModifiable modifiable)) return false;
        ToolDefinition definition = modifiable.getToolDefinition();
        if (!definition.isDataLoaded()) return false;

        boolean supportsAction = definition.getData().getHook(ToolHooks.TOOL_ACTION)
            .canPerformAction(ToolStack.from(stack), requiredAction);
        if (!supportsAction) return false;

        ResourceLocation toolId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return toolId == null || !ToolExclusionConfig.isExcluded(toolType, toolId.toString());
    }

    // ── Armor slot matching ──────────────────────────────────────────────

    private boolean matchesArmorSlot(ItemStack stack) {
        if (!(stack.getItem() instanceof ModifiableArmorItem armorItem)) return false;
        ArmorItem.Type required = VanillaItemMappings.getArmorType(toolType);
        if (required == null || armorItem.getType() != required) return false;

        ResourceLocation armorId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (armorId == null) return false;

        // Filter by armor set prefix (e.g., "tconstruct:travelers_" or "tconstruct:plate_")
        if (armorSet != null && !armorId.toString().startsWith("tconstruct:" + armorSet + "_")) return false;

        return !ToolExclusionConfig.isExcluded(toolType, armorId.toString());
    }

    // ── Ranged matching ─────────────────────────────────────────────────

    private boolean testRanged(ItemStack stack) {
        if (partTiers == null || partTiers.isEmpty()) return false;

        if (!matchesRangedType(stack)) return false;

        CompoundTag nbt = stack.getTag();
        if (nbt == null || !nbt.contains("tic_materials", Tag.TAG_LIST)) return false;

        ListTag materialsList = nbt.getList("tic_materials", Tag.TAG_STRING);
        if (materialsList.isEmpty()) return false;

        // Per-part tier floor: each part's IMaterial.getTier() >= required floor
        for (int i = 0; i < partTiers.size() && i < materialsList.size(); i++) {
            Integer requiredFloor = TIER_NAME_TO_INT.get(partTiers.get(i).toLowerCase());
            if (requiredFloor == null) return false;

            MaterialId materialId = MaterialId.tryParse(materialsList.getString(i));
            if (materialId == null) return false;

            IMaterial material = MaterialRegistry.getInstance().getMaterial(materialId);
            if (material == IMaterial.UNKNOWN) return false;

            if (material.getTier() < requiredFloor) return false;
        }

        return true;
    }

    private boolean matchesRangedType(ItemStack stack) {
        if (toolType == null || toolType.isEmpty()) return false;

        boolean matchesType = switch (toolType.toLowerCase()) {
            case "bow"      -> stack.getItem() instanceof ModifiableBowItem;
            case "crossbow" -> stack.getItem() instanceof ModifiableCrossbowItem;
            default -> false;
        };
        if (!matchesType) return false;

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return itemId == null || !ToolExclusionConfig.isExcluded(toolType, itemId.toString());
    }

    // ── Display items (JEI) ──────────────────────────────────────────────

    @Override
    public ItemStack[] getItems() {
        return switch (matchMode) {
            case TOOL_ACTION -> getToolItems();
            case ARMOR_SLOT  -> getArmorItems();
            case RANGED      -> getRangedItems();
        };
    }

    private ItemStack[] getToolItems() {
        ToolAction action = VanillaItemMappings.getToolAction(toolType);
        if (action == null) return new ItemStack[0];
        String cacheKey = toolType + ":" + requiredTier;
        return DISPLAY_CACHE.computeIfAbsent(cacheKey, k -> buildDisplayItems(
            MaterialMappingConfig.getCanonicalToolMaterial(requiredTier), requiredTier, TcItemRegistry.getEligibleTools(action, toolType)));
    }

    private ItemStack[] getArmorItems() {
        ArmorItem.Type armorType = VanillaItemMappings.getArmorType(toolType);
        if (armorType == null || armorSet == null) return new ItemStack[0];
        String tierKey = armorSet + ":" + minTier + "-" + maxTier;
        String cacheKey = "armor:" + toolType + ":" + tierKey;
        String canonicalId = MaterialMappingConfig.getCanonicalArmorMaterial(armorSet, minTier, maxTier);
        // Derive human-readable label from canonical material (e.g., "tconstruct:cobalt" → "cobalt")
        String displayLabel = canonicalId != null
            ? canonicalId.substring(canonicalId.indexOf(':') + 1)
            : armorSet;
        return DISPLAY_CACHE.computeIfAbsent(cacheKey, k -> {
            ItemStack[] items = buildDisplayItems(canonicalId, displayLabel,
                TcItemRegistry.getEligibleArmor(armorType, armorSet, toolType));
            for (ItemStack item : items) {
                item.getOrCreateTag().putString("nmvt_match_mode", "armor_slot");
            }
            return items;
        });
    }

    private ItemStack[] getRangedItems() {
        if (toolType == null || partTiers == null || partTiers.isEmpty()) return new ItemStack[0];
        String cacheKey = "ranged:" + toolType;
        String canonicalId = MaterialMappingConfig.getCanonicalToolMaterial(partTiers.get(0));
        String displayLabel = partTiers.get(0);
        return DISPLAY_CACHE.computeIfAbsent(cacheKey, k -> {
            ItemStack[] items = buildDisplayItems(canonicalId, displayLabel,
                TcItemRegistry.getEligibleRanged(toolType));
            for (ItemStack item : items) {
                item.getOrCreateTag().putString("nmvt_match_mode", "ranged");
            }
            return items;
        });
    }

    // ── Shared display building ──────────────────────────────────────────

    /**
     * Builds display ItemStacks for JEI from a list of valid items and a canonical material.
     * Canonical material is resolved by MaterialMappingConfig (shared with loot generation).
     */
    private static ItemStack[] buildDisplayItems(@Nullable String canonicalId, String tierLabel, List<? extends IModifiable> items) {
        if (canonicalId == null) return new ItemStack[0];

        MaterialVariantId variantId = MaterialVariantId.tryParse(canonicalId);
        if (variantId == null) return new ItemStack[0];
        MaterialVariant displayVariant = MaterialVariant.of(variantId);

        List<ItemStack> displayItems = new ArrayList<>();
        for (IModifiable item : items) {
            ItemStack displayStack = ToolBuildHandler.createSingleMaterial(item, displayVariant);
            if (!displayStack.isEmpty()) {
                displayStack.getOrCreateTag().putString("nmvt_required_tier", tierLabel);
                displayItems.add(displayStack);
            }
        }
        return displayItems.toArray(new ItemStack[0]);
    }

    // ── AbstractIngredient overrides ─────────────────────────────────────

    @Override public boolean isSimple() { return false; }
    @Override public boolean isEmpty() { return false; }

    @Override
    public IIngredientSerializer<? extends TinkerMaterialIngredient> getSerializer() {
        return ModRecipeSerializers.get();
    }

    @Override
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "nomorevanillatools:tinker_material");
        json.addProperty("mode", matchMode.name().toLowerCase());
        if (matchMode == MatchMode.TOOL_ACTION) {
            json.addProperty("tool_type", toolType);
            json.addProperty("tier", requiredTier);
        } else if (matchMode == MatchMode.ARMOR_SLOT) {
            json.addProperty("tool_type", toolType);
            json.addProperty("armor_set", armorSet);
            json.addProperty("min_tier", minTier);
            json.addProperty("max_tier", maxTier);
        } else {
            json.addProperty("ranged_type", toolType);
            JsonArray tiers = new JsonArray();
            if (partTiers != null) for (String t : partTiers) tiers.add(t);
            json.add("part_tiers", tiers);
        }
        return json;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String getRequiredTier() { return requiredTier; }
    public String getToolType() { return toolType; }
    public MatchMode getMatchMode() { return matchMode; }
    public @Nullable String getArmorSet() { return armorSet; }
    public int getMinTier() { return minTier; }
    public int getMaxTier() { return maxTier; }
    public @Nullable List<String> getPartTiers() { return partTiers; }

    // ── Serializer ───────────────────────────────────────────────────────

    public static class Serializer implements IIngredientSerializer<TinkerMaterialIngredient> {

        public static final Serializer INSTANCE = new Serializer();

        @Override
        public TinkerMaterialIngredient parse(JsonObject json) {
            MatchMode mode = MatchMode.valueOf(json.get("mode").getAsString().toUpperCase());
            if (mode == MatchMode.TOOL_ACTION) {
                String toolType = json.get("tool_type").getAsString();
                String tier = json.get("tier").getAsString();
                return new TinkerMaterialIngredient(tier, toolType, mode);
            } else if (mode == MatchMode.ARMOR_SLOT) {
                String toolType = json.get("tool_type").getAsString();
                String armorSet = json.get("armor_set").getAsString();
                int minTier = json.get("min_tier").getAsInt();
                int maxTier = json.get("max_tier").getAsInt();
                return new TinkerMaterialIngredient(null, toolType, mode, armorSet, minTier, maxTier, null);
            } else {
                String rangedType = json.get("ranged_type").getAsString();
                List<String> partTiers = new ArrayList<>();
                json.getAsJsonArray("part_tiers").forEach(e -> partTiers.add(e.getAsString()));
                return new TinkerMaterialIngredient(rangedType, mode, partTiers);
            }
        }

        @Override
        public TinkerMaterialIngredient parse(FriendlyByteBuf buffer) {
            MatchMode mode = buffer.readEnum(MatchMode.class);
            if (mode == MatchMode.TOOL_ACTION) {
                String toolType = buffer.readUtf();
                String tier = buffer.readUtf();
                return new TinkerMaterialIngredient(tier, toolType, mode);
            } else if (mode == MatchMode.ARMOR_SLOT) {
                String toolType = buffer.readUtf();
                String armorSet = buffer.readUtf();
                int minTier = buffer.readVarInt();
                int maxTier = buffer.readVarInt();
                return new TinkerMaterialIngredient(null, toolType, mode, armorSet, minTier, maxTier, null);
            } else {
                String rangedType = buffer.readUtf();
                int count = buffer.readVarInt();
                List<String> partTiers = new ArrayList<>(count);
                for (int i = 0; i < count; i++) partTiers.add(buffer.readUtf());
                return new TinkerMaterialIngredient(rangedType, mode, partTiers);
            }
        }

        @Override
        public void write(FriendlyByteBuf buffer, TinkerMaterialIngredient ingredient) {
            buffer.writeEnum(ingredient.matchMode);
            if (ingredient.matchMode == MatchMode.TOOL_ACTION) {
                buffer.writeUtf(ingredient.toolType);
                buffer.writeUtf(ingredient.requiredTier != null ? ingredient.requiredTier : "");
            } else if (ingredient.matchMode == MatchMode.ARMOR_SLOT) {
                buffer.writeUtf(ingredient.toolType);
                buffer.writeUtf(ingredient.armorSet != null ? ingredient.armorSet : "");
                buffer.writeVarInt(ingredient.minTier);
                buffer.writeVarInt(ingredient.maxTier);
            } else {
                buffer.writeUtf(ingredient.toolType); // stores rangedType
                buffer.writeVarInt(ingredient.partTiers != null ? ingredient.partTiers.size() : 0);
                if (ingredient.partTiers != null) {
                    for (String tier : ingredient.partTiers) buffer.writeUtf(tier);
                }
            }
        }
    }
}
