package com.pwazta.nomorevanillatools.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pwazta.nomorevanillatools.Config;
import com.pwazta.nomorevanillatools.config.MaterialMappingConfig;
import com.pwazta.nomorevanillatools.config.ToolExclusionConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.common.crafting.AbstractIngredient;
import net.minecraftforge.common.crafting.IIngredientSerializer;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import slimeknights.tconstruct.library.materials.definition.MaterialVariant;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.tools.definition.ToolDefinition;
import slimeknights.tconstruct.library.tools.definition.module.ToolHooks;
import slimeknights.tconstruct.library.tools.helper.ToolBuildHandler;
import slimeknights.tconstruct.library.tools.item.IModifiable;
import slimeknights.tconstruct.library.tools.item.IModifiableDisplay;
import slimeknights.tconstruct.library.tools.item.armor.ModifiableArmorItem;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Custom ingredient that matches Tinker's Construct tools or armor by material tier and type.
 *
 * Two modes:
 * - TOOL_ACTION: matches assembled TC tools by head material tier and ToolAction capability
 * - ARMOR_SLOT: matches TC armor by plating material tier and ArmorItem.Type slot
 *
 * In both modes, head/plating material (index 0 in tic_materials) must match the required tier.
 * For tools, a configurable percentage of other parts can optionally be required to match.
 */
public class TinkerMaterialIngredient extends AbstractIngredient {
    /** Matching mode: tool action (pickaxe, sword, etc.) or armor slot (helmet, chestplate, etc.) */
    public enum MatchMode { TOOL_ACTION, ARMOR_SLOT }

    // ── Caches (ConcurrentHashMap for thread safety between client/server threads) ──

    /** Cache of valid items per type key. Tools keyed by action name, armor by slot name. */
    private static final Map<String, List<IModifiable>> ITEM_CACHE = new ConcurrentHashMap<>();

    /** Cache of display ItemStacks per type:tier key. Built from ITEM_CACHE + canonical material. */
    private static final Map<String, ItemStack[]> DISPLAY_CACHE = new ConcurrentHashMap<>();

    /** Preferred display material per tool tier. */
    private static final Map<String, String> CANONICAL_DISPLAY_MATERIAL = Map.of(
        "wooden",    "tconstruct:wood",
        "stone",     "tconstruct:rock",
        "iron",      "tconstruct:iron",
        "golden",    "tconstruct:gold",
        "diamond",   "tconstruct:cobalt",
        "netherite", "tconstruct:hepatizon"
    );

    /** Preferred display material per armor tier. Separate because armor has leather tier. */
    private static final Map<String, String> ARMOR_CANONICAL_DISPLAY_MATERIAL = Map.of(
        "leather",   "tconstruct:copper",
        "iron",      "tconstruct:iron",
        "golden",    "tconstruct:gold",
        "diamond",   "tconstruct:cobalt",
        "netherite", "tconstruct:hepatizon"
    );

    /** Clears all caches. Called when exclusions or material mappings change. */
    public static void clearDisplayCache() {
        ITEM_CACHE.clear();
        DISPLAY_CACHE.clear();
    }

    // ── Instance fields ──────────────────────────────────────────────────

    private final String requiredTier; // e.g., "wooden", "iron", "leather", "diamond"
    private final String toolType;     // e.g., "sword", "pickaxe", "helmet", "chestplate"
    private final MatchMode matchMode;

    protected TinkerMaterialIngredient(String requiredTier, String toolType, MatchMode matchMode) {
        super(Stream.empty());
        this.requiredTier = requiredTier;
        this.toolType = toolType;
        this.matchMode = matchMode;
    }

    // ── test() — main matching logic ─────────────────────────────────────

    @Override
    public boolean test(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return switch (matchMode) {
            case TOOL_ACTION -> testTool(stack);
            case ARMOR_SLOT  -> testArmor(stack);
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

        Set<String> allowedMaterials = MaterialMappingConfig.getArmorMaterialsForTier(requiredTier);
        if (allowedMaterials == null || allowedMaterials.isEmpty()) return false;

        // Plating material (index 0) MUST match
        if (!allowedMaterials.contains(materialsList.getString(0))) return false;

        // Note: requireOtherPartsMatch is skipped for armor — inner parts (maille, cuirass)
        // use different stat types than plating and are not in the armor tier map.

        return matchesArmorSlot(stack);
    }

    // ── Tool type matching ───────────────────────────────────────────────

    private boolean matchesToolType(ItemStack stack) {
        if (toolType == null || toolType.isEmpty()) return true;
        ToolAction requiredAction = getRequiredToolAction();
        if (requiredAction == null) return true;

        if (!(stack.getItem() instanceof IModifiable modifiable)) return false;
        ToolDefinition definition = modifiable.getToolDefinition();
        if (!definition.isDataLoaded()) return false;

        boolean supportsAction = definition.getData()
            .getHook(ToolHooks.TOOL_ACTION)
            .canPerformAction(ToolStack.from(stack), requiredAction);
        if (!supportsAction) return false;

        ResourceLocation toolId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return toolId == null || !ToolExclusionConfig.isExcluded(toolType, toolId.toString());
    }

    private @Nullable ToolAction getRequiredToolAction() {
        return switch (toolType.toLowerCase()) {
            case "pickaxe" -> ToolActions.PICKAXE_DIG;
            case "axe"     -> ToolActions.AXE_DIG;
            case "sword"   -> ToolActions.SWORD_DIG;
            case "shovel"  -> ToolActions.SHOVEL_DIG;
            case "hoe"     -> ToolActions.HOE_DIG;
            default        -> null;
        };
    }

    // ── Armor slot matching ──────────────────────────────────────────────

    private boolean matchesArmorSlot(ItemStack stack) {
        if (!(stack.getItem() instanceof ModifiableArmorItem armorItem)) return false;
        ArmorItem.Type required = getRequiredArmorType();
        if (required == null || armorItem.getType() != required) return false;

        ResourceLocation armorId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return armorId == null || !ToolExclusionConfig.isExcluded(toolType, armorId.toString());
    }

    private @Nullable ArmorItem.Type getRequiredArmorType() {
        return switch (toolType.toLowerCase()) {
            case "helmet"     -> ArmorItem.Type.HELMET;
            case "chestplate" -> ArmorItem.Type.CHESTPLATE;
            case "leggings"   -> ArmorItem.Type.LEGGINGS;
            case "boots"      -> ArmorItem.Type.BOOTS;
            default           -> null;
        };
    }

    // ── Display items (JEI) ──────────────────────────────────────────────

    @Override
    public ItemStack[] getItems() {
        return switch (matchMode) {
            case TOOL_ACTION -> getToolItems();
            case ARMOR_SLOT  -> getArmorItems();
        };
    }

    private ItemStack[] getToolItems() {
        ToolAction action = getRequiredToolAction();
        if (action == null) return new ItemStack[0];
        String cacheKey = toolType + ":" + requiredTier;
        return DISPLAY_CACHE.computeIfAbsent(cacheKey, k ->
            buildDisplayItems(
                MaterialMappingConfig.getMaterialsForTier(requiredTier),
                CANONICAL_DISPLAY_MATERIAL, requiredTier,
                scanToolsForAction(action, toolType)));
    }

    private ItemStack[] getArmorItems() {
        ArmorItem.Type armorType = getRequiredArmorType();
        if (armorType == null) return new ItemStack[0];
        String cacheKey = "armor:" + toolType + ":" + requiredTier;
        return DISPLAY_CACHE.computeIfAbsent(cacheKey, k ->
            buildDisplayItems(
                MaterialMappingConfig.getArmorMaterialsForTier(requiredTier),
                ARMOR_CANONICAL_DISPLAY_MATERIAL, requiredTier,
                scanArmorForSlot(armorType, toolType)));
    }

    // ── Item scanning (cached per type) ──────────────────────────────────

    /** Scans registry for TC tools supporting the given ToolAction. Cached per action name. */
    private static List<IModifiable> scanToolsForAction(ToolAction requiredAction, String actionName) {
        return ITEM_CACHE.computeIfAbsent(actionName, k -> {
            List<IModifiable> tools = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS) {
                if (!(item instanceof IModifiableDisplay display)) continue;
                if (!(item instanceof IModifiable modifiable)) continue;
                ToolDefinition definition = display.getToolDefinition();
                if (!definition.isDataLoaded()) continue;

                ItemStack renderStack = display.getRenderTool();
                boolean supportsAction = definition.getData()
                    .getHook(ToolHooks.TOOL_ACTION)
                    .canPerformAction(ToolStack.from(renderStack), requiredAction);
                if (!supportsAction) continue;

                ResourceLocation toolId = ForgeRegistries.ITEMS.getKey(item);
                if (toolId != null && ToolExclusionConfig.isExcluded(actionName, toolId.toString())) continue;

                tools.add(modifiable);
            }
            return tools;
        });
    }

    /** Scans registry for TC armor matching the given slot. Cached per slot name. */
    private static List<IModifiable> scanArmorForSlot(ArmorItem.Type requiredType, String slotName) {
        return ITEM_CACHE.computeIfAbsent(slotName, k -> {
            List<IModifiable> armor = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS) {
                if (!(item instanceof ModifiableArmorItem armorItem)) continue;
                if (armorItem.getType() != requiredType) continue;

                ResourceLocation armorId = ForgeRegistries.ITEMS.getKey(item);
                if (armorId != null && ToolExclusionConfig.isExcluded(slotName, armorId.toString())) continue;

                armor.add(armorItem);
            }
            return armor;
        });
    }

    // ── Shared display building ──────────────────────────────────────────

    /**
     * Builds display ItemStacks for JEI from a list of valid items and a material tier.
     * Uses a canonical display material per tier for consistent JEI appearance.
     */
    private static ItemStack[] buildDisplayItems(
            Set<String> materials,
            Map<String, String> canonicalMap,
            String requiredTier,
            List<? extends IModifiable> items) {
        if (materials == null || materials.isEmpty()) return new ItemStack[0];

        String canonicalId = canonicalMap.get(requiredTier);
        if (canonicalId == null || !materials.contains(canonicalId)) {
            canonicalId = materials.iterator().next();
        }
        MaterialVariantId variantId = MaterialVariantId.tryParse(canonicalId);
        if (variantId == null) return new ItemStack[0];
        MaterialVariant displayVariant = MaterialVariant.of(variantId);

        List<ItemStack> displayItems = new ArrayList<>();
        for (IModifiable item : items) {
            ItemStack displayStack = ToolBuildHandler.createSingleMaterial(item, displayVariant);
            if (!displayStack.isEmpty()) {
                displayStack.getOrCreateTag().putString("nmvt_required_tier", requiredTier);
                displayItems.add(displayStack);
            }
        }
        return displayItems.toArray(new ItemStack[0]);
    }

    // ── AbstractIngredient overrides ─────────────────────────────────────

    @Override
    public boolean isSimple() {
        return false;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public IIngredientSerializer<? extends TinkerMaterialIngredient> getSerializer() {
        return ModRecipeSerializers.get();
    }

    @Override
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "nomorevanillatools:tinker_material");
        json.addProperty("tier", requiredTier);
        json.addProperty("tool_type", toolType);
        json.addProperty("mode", matchMode.name().toLowerCase());
        return json;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String getRequiredTier() {
        return requiredTier;
    }

    public String getToolType() {
        return toolType;
    }

    public MatchMode getMatchMode() {
        return matchMode;
    }

    // ── Serializer ───────────────────────────────────────────────────────

    public static class Serializer implements IIngredientSerializer<TinkerMaterialIngredient> {

        public static final Serializer INSTANCE = new Serializer();

        @Override
        public TinkerMaterialIngredient parse(JsonObject json) {
            String tier = json.get("tier").getAsString();
            String toolType = json.get("tool_type").getAsString();
            MatchMode mode = MatchMode.valueOf(json.get("mode").getAsString().toUpperCase());
            return new TinkerMaterialIngredient(tier, toolType, mode);
        }

        @Override
        public TinkerMaterialIngredient parse(FriendlyByteBuf buffer) {
            String tier = buffer.readUtf();
            String toolType = buffer.readUtf();
            MatchMode mode = buffer.readEnum(MatchMode.class);
            return new TinkerMaterialIngredient(tier, toolType, mode);
        }

        @Override
        public void write(FriendlyByteBuf buffer, TinkerMaterialIngredient ingredient) {
            buffer.writeUtf(ingredient.requiredTier);
            buffer.writeUtf(ingredient.toolType);
            buffer.writeEnum(ingredient.matchMode);
        }
    }
}
