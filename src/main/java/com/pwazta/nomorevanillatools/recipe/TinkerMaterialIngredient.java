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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.common.crafting.AbstractIngredient;
import net.minecraftforge.common.crafting.IIngredientSerializer;
import net.minecraftforge.registries.ForgeRegistries;
import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import slimeknights.tconstruct.library.materials.definition.MaterialVariant;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.tools.definition.ToolDefinition;
import slimeknights.tconstruct.library.tools.definition.module.ToolHooks;
import slimeknights.tconstruct.library.tools.helper.ToolBuildHandler;
import slimeknights.tconstruct.library.tools.item.IModifiable;
import slimeknights.tconstruct.library.tools.item.IModifiableDisplay;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Custom ingredient that matches Tinker's Construct tools by material tier and tool type.
 * Matching: head material (index 0) must match the required tier. Tool type is validated
 * via TC's ToolAction system (e.g., PICKAXE_DIG, SWORD_DIG), which naturally supports
 * multi-tools (a pickadze satisfies both pickaxe and shovel recipes).
 * Optionally, a configurable percentage of non-head parts must also match the tier.
 */
public class TinkerMaterialIngredient extends AbstractIngredient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Cache of valid tools per toolType. Scanned once per action from ForgeRegistries.ITEMS. */
    private static final Map<String, List<IModifiable>> TOOL_CACHE = new HashMap<>();

    /** Cache of display ItemStacks per toolType:tier. Built from TOOL_CACHE + canonical material. */
    private static final Map<String, ItemStack[]> DISPLAY_CACHE = new HashMap<>();

    /** Preferred display material per tier — the material shown in JEI for each tier's recipe slot. */
    private static final Map<String, String> CANONICAL_DISPLAY_MATERIAL = Map.of(
        "wooden",   "tconstruct:wood",
        "stone",    "tconstruct:rock",
        "iron",     "tconstruct:iron",
        "golden",   "tconstruct:gold",
        "diamond",  "tconstruct:cobalt",
        "netherite", "tconstruct:hepatizon"
    );

    /** Clears both caches. Called when exclusions or material mappings change. */
    public static void clearDisplayCache() {
        TOOL_CACHE.clear();
        DISPLAY_CACHE.clear();
    }

    private final String requiredTier; // e.g., "wooden", "stone", "iron", "golden", "diamond"
    private final String toolType;     // e.g., "sword", "pickaxe", "axe", "shovel", "hoe"

    /**
     * Creates a new TinkerMaterialIngredient.
     *
     * @param requiredTier The vanilla material tier (wooden, stone, iron, golden, diamond)
     * @param toolType The tool type (sword, pickaxe, axe, shovel, hoe)
     */
    protected TinkerMaterialIngredient(String requiredTier, String toolType) {
        super(Stream.empty()); // No vanilla representation - completely custom matching
        this.requiredTier = requiredTier;
        this.toolType = toolType;
    }

    /**
     * Tests if the given ItemStack matches this ingredient.
     * Returns true if:
     * 1. The stack is a Tinker's Construct tool
     * 2. The tool's HEAD material (index 0) matches the required tier
     * 3. Optionally, a configurable percentage of other parts also match
     *
     * @param stack The ItemStack to test
     * @return true if the stack matches, false otherwise
     */
    @Override
    public boolean test(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        // Check if the stack has NBT data
        CompoundTag nbt = stack.getTag();
        if (nbt == null) {
            return false;
        }

        // Check if it has the tic_materials array (indicates it's a TC tool)
        // TODO: If TC ever changes the "tic_materials" NBT key (unlikely, it's stable), update this.
        //       Alternative: use ToolStack.from(stack).getMaterials() for API safety at slight perf cost.
        if (!nbt.contains("tic_materials", Tag.TAG_LIST)) {
            return false;
        }

        // Extract the tic_materials list
        ListTag materialsList = nbt.getList("tic_materials", Tag.TAG_STRING);
        if (materialsList.isEmpty()) {
            return false;
        }

        // Get the configured materials for this tier from the material mapping config
        Set<String> allowedMaterials = MaterialMappingConfig.getMaterialsForTier(requiredTier);
        if (allowedMaterials == null || allowedMaterials.isEmpty()) {
            // If no materials configured for this tier, fail
            return false;
        }

        // CRITICAL: Check HEAD material (index 0) FIRST - MUST match
        String headMaterial = materialsList.getString(0);
        if (!allowedMaterials.contains(headMaterial)) {
            // Head doesn't match tier → fail immediately
            // This ensures that even if other parts match, the tool must have the correct head
            return false;
        }

        // If config requires additional part matching, check total parts ratio
        if (Config.requireOtherPartsMatch && materialsList.size() > 1) {
            int matchingCount = 1; // Head already verified as matching above
            int totalParts = materialsList.size();

            // Count matching parts among non-head slots (index 1+)
            for (int i = 1; i < materialsList.size(); i++) {
                if (allowedMaterials.contains(materialsList.getString(i))) {
                    matchingCount++;
                }
            }

            // Check if percentage of TOTAL parts meets threshold
            double totalPartsRatio = (double) matchingCount / totalParts;
            if (totalPartsRatio < Config.otherPartsThreshold) {
                return false;
            }
        }

        return matchesToolType(stack);
    }

    /**
     * Returns display stacks for JEI. Uses two-tier cache:
     * 1. TOOL_CACHE: registry scan per toolType (5 scans total, shared across tiers)
     * 2. DISPLAY_CACHE: applies canonical material per toolType:tier combo
     */
    @Override
    public ItemStack[] getItems() {
        ToolAction action = getRequiredToolAction();
        if (action == null) return new ItemStack[0];
        String cacheKey = toolType + ":" + requiredTier;
        return DISPLAY_CACHE.computeIfAbsent(cacheKey, k -> buildDisplayItems(action, requiredTier, toolType));
    }

    /**
     * Scans ForgeRegistries.ITEMS once per toolType for all TC tools that support the
     * ToolAction and aren't excluded. Result is cached in TOOL_CACHE.
     */
    private static List<IModifiable> getToolsForAction(ToolAction requiredAction, String actionName) {
        return TOOL_CACHE.computeIfAbsent(actionName, k -> {
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
                if (toolId != null && ToolExclusionConfig.isExcluded(actionName, toolId.toString())) {
                    continue;
                }

                tools.add(modifiable);
            }
            return tools;
        });
    }

    /**
     * Builds display stacks by applying the canonical material to each cached tool.
     * Registry scan is handled by getToolsForAction() — this just applies the material.
     */
    private static ItemStack[] buildDisplayItems(ToolAction requiredAction, String requiredTier, String actionName) {
        Set<String> materials = MaterialMappingConfig.getMaterialsForTier(requiredTier);
        if (materials == null || materials.isEmpty()) {
            return new ItemStack[0];
        }

        String canonicalId = CANONICAL_DISPLAY_MATERIAL.get(requiredTier);
        if (canonicalId == null || !materials.contains(canonicalId)) {
            canonicalId = materials.iterator().next();
        }
        MaterialVariantId variantId = MaterialVariantId.tryParse(canonicalId);
        if (variantId == null) {
            return new ItemStack[0];
        }
        MaterialVariant displayVariant = MaterialVariant.of(variantId);

        List<IModifiable> tools = getToolsForAction(requiredAction, actionName);
        List<ItemStack> displayItems = new ArrayList<>();
        for (IModifiable tool : tools) {
            ItemStack displayStack = ToolBuildHandler.createSingleMaterial(tool, displayVariant);
            if (!displayStack.isEmpty()) {
                displayStack.getOrCreateTag().putString("nmvt_required_tier", requiredTier);
                displayItems.add(displayStack);
            }
        }
        return displayItems.toArray(new ItemStack[0]);
    }

    /**
     * Checks if the item supports the required ToolAction via its ToolDefinition hook.
     * Uses the definition hook directly instead of ModifierUtil — broken tools should
     * still be valid crafting ingredients.
     */
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

        // Check exclusion config — tool supports the action but may be excluded by user config
        ResourceLocation toolId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (toolId != null && ToolExclusionConfig.isExcluded(toolType, toolId.toString())) {
            return false;
        }

        return true;
    }

    /**
     * Maps the toolType string to the corresponding Forge ToolAction constant.
     *
     * @return The ToolAction for this ingredient's tool type, or null if unknown
     */
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

    /**
     * This ingredient is not simple because it requires NBT checking.
     * Simple ingredients can be represented by a simple item/tag list.
     *
     * @return false, indicating this is a complex ingredient
     */
    @Override
    public boolean isSimple() {
        return false;
    }

    /**
     * This ingredient is never empty — it always represents a required tool.
     * Must override because the base Ingredient.isEmpty() checks values.length == 0,
     * which is true since we pass Stream.empty() to super(). Without this override,
     * recipe transfer handlers skip this ingredient entirely.
     */
    @Override
    public boolean isEmpty() {
        return false;
    }

    /**
     * Gets the serializer for this ingredient type.
     *
     * @return The ingredient serializer
     */
    @Override
    public IIngredientSerializer<? extends TinkerMaterialIngredient> getSerializer() {
        return ModRecipeSerializers.get();
    }

    /**
     * Serializes this ingredient to JSON for recipe files.
     *
     * @return JSON representation of this ingredient
     */
    @Override
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "nomorevanillatools:tinker_material");
        json.addProperty("tier", requiredTier);
        json.addProperty("tool_type", toolType);
        return json;
    }

    /**
     * Gets the required material tier.
     *
     * @return The required tier (wooden, stone, iron, golden, diamond)
     */
    public String getRequiredTier() {
        return requiredTier;
    }

    /**
     * Gets the tool type.
     *
     * @return The tool type (sword, pickaxe, axe, shovel, hoe)
     */
    public String getToolType() {
        return toolType;
    }

    /**
     * Serializer for TinkerMaterialIngredient.
     * Handles conversion to/from JSON and network packets.
     */
    public static class Serializer implements IIngredientSerializer<TinkerMaterialIngredient> {

        public static final Serializer INSTANCE = new Serializer();

        /**
         * Deserializes from JSON (used when loading recipes from files).
         *
         * @param json The JSON object containing tier and tool_type
         * @return A new TinkerMaterialIngredient instance
         */
        @Override
        public TinkerMaterialIngredient parse(JsonObject json) {
            String tier = json.get("tier").getAsString();
            String toolType = json.get("tool_type").getAsString();
            return new TinkerMaterialIngredient(tier, toolType);
        }

        /**
         * Deserializes from network buffer (used when syncing to clients).
         *
         * @param buffer The network buffer
         * @return A new TinkerMaterialIngredient instance
         */
        @Override
        public TinkerMaterialIngredient parse(FriendlyByteBuf buffer) {
            String tier = buffer.readUtf();
            String toolType = buffer.readUtf();
            return new TinkerMaterialIngredient(tier, toolType);
        }

        /**
         * Serializes to network buffer (used when syncing to clients).
         *
         * @param buffer The network buffer
         * @param ingredient The ingredient to serialize
         */
        @Override
        public void write(FriendlyByteBuf buffer, TinkerMaterialIngredient ingredient) {
            buffer.writeUtf(ingredient.requiredTier);
            buffer.writeUtf(ingredient.toolType);
        }
    }
}
