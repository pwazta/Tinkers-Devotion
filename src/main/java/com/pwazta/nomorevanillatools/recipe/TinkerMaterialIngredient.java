package com.pwazta.nomorevanillatools.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pwazta.nomorevanillatools.Config;
import com.pwazta.nomorevanillatools.config.MaterialMappingConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.common.crafting.AbstractIngredient;
import net.minecraftforge.common.crafting.IIngredientSerializer;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import slimeknights.tconstruct.library.tools.definition.ToolDefinition;
import slimeknights.tconstruct.library.tools.definition.module.ToolHooks;
import slimeknights.tconstruct.library.tools.item.IModifiable;
import slimeknights.tconstruct.library.tools.item.IModifiableDisplay;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;

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

    private final String requiredTier; // e.g., "wooden", "stone", "iron", "golden", "diamond"
    private final String toolType;     // e.g., "sword", "pickaxe", "axe", "shovel", "hoe"

    private ItemStack[] cachedDisplayItems = null;

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

        // If config requires additional part matching, check other parts
        if (Config.requireOtherPartsMatch && materialsList.size() > 1) {
            int matchingCount = 0;
            int otherPartCount = materialsList.size() - 1;  // Exclude head

            // Start from index 1 (skip head - already verified)
            for (int i = 1; i < materialsList.size(); i++) {
                if (allowedMaterials.contains(materialsList.getString(i))) {
                    matchingCount++;
                }
            }

            // Check if percentage of other parts meets threshold
            double otherPartsRatio = (double) matchingCount / otherPartCount;
            if (otherPartsRatio < Config.otherPartsThreshold) {
                return false;
            }
        }

        return matchesToolType(stack);
    }

    /**
     * Returns display ItemStacks for JEI and recipe book.
     * Creates TC tools with appropriate materials for the required tier.
     *
     * @return Array of ItemStacks to display in recipe viewers
     */
    @Override
    public ItemStack[] getItems() {
        if (cachedDisplayItems == null) {
            cachedDisplayItems = buildDisplayItems();
        }
        return cachedDisplayItems;
    }

    /**
     * Builds the display ItemStacks for this ingredient.
     * Uses TC's built-in IModifiableDisplay.getRenderTool() which is always available
     * (uses hardcoded render materials, no config/data dependency).
     */
    private ItemStack[] buildDisplayItems() {
        String tcToolName = mapToolTypeToTC(toolType);
        if (tcToolName == null) {
            return new ItemStack[0];
        }

        ResourceLocation toolId = new ResourceLocation("tconstruct", tcToolName);
        Item tool = ForgeRegistries.ITEMS.getValue(toolId);
        if (tool == null || tool == Items.AIR) {
            return new ItemStack[0];
        }

        if (tool instanceof IModifiableDisplay display) {
            return new ItemStack[]{ display.getRenderTool() };
        }

        return new ItemStack[0];
    }

    /**
     * Maps vanilla tool type names to Tinker's Construct tool registry names.
     *
     * @param vanillaType The vanilla tool type (pickaxe, axe, sword, shovel, hoe)
     * @return The TC tool name, or null if not mappable
     */
    private String mapToolTypeToTC(String vanillaType) {
        return switch (vanillaType.toLowerCase()) {
            case "pickaxe" -> "pickaxe";
            case "axe" -> "hand_axe";
            case "sword" -> "sword";
            case "shovel" -> "mattock";
            case "hoe" -> "kama";
            default -> null;
        };
    }

    /**
     * Checks if the given ItemStack's tool type matches the expected tool type for this ingredient.
     * Uses TC's ToolAction system (e.g., PICKAXE_DIG, SWORD_DIG) which handles multi-tools
     * correctly (a pickadze satisfies both pickaxe and shovel recipes).
     */
    private boolean matchesToolType(ItemStack stack) {
        if (toolType == null || toolType.isEmpty()) {
            return true;
        }

        ToolAction requiredAction = getRequiredToolAction();
        if (requiredAction == null) {
            return true; // unrecognized tool type, skip validation
        }

        Item item = stack.getItem();

        // Uses TC's ToolAction system via ToolDefinition hook directly (NOT ModifierUtil,
        // which rejects broken tools — broken tools should still be valid crafting ingredients).
        if (item instanceof IModifiable modifiable) {
            ToolDefinition definition = modifiable.getToolDefinition();
            if (definition.isDataLoaded()) {
                return definition.getData().getHook(ToolHooks.TOOL_ACTION)
                    .canPerformAction(ToolStack.from(stack), requiredAction);
            }
        }
        return false;
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
