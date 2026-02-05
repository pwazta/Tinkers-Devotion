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
import net.minecraftforge.common.crafting.AbstractIngredient;
import net.minecraftforge.common.crafting.IIngredientSerializer;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.tools.definition.ToolDefinition;
import slimeknights.tconstruct.library.tools.definition.module.material.ToolMaterialHook;
import slimeknights.tconstruct.library.tools.item.IModifiable;
import slimeknights.tconstruct.library.tools.nbt.MaterialNBT;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Custom ingredient that matches Tinker's Construct tools based on material composition.
 * A tool matches if its head is made from the required material tier and (configurable) 
 * at least  50% of its parts are made from the required material tier.
 */
public class TinkerMaterialIngredient extends AbstractIngredient {

    private final String requiredTier; // e.g., "wooden", "stone", "iron", "golden", "diamond"
    private final String toolType;     // e.g., "sword", "pickaxe", "axe", "shovel", "hoe"

    // Cached display items for JEI/recipe book
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
            return otherPartsRatio >= Config.otherPartsThreshold;
        }

        // If only head checking is required (default), we already passed
        return true;
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
     * Creates TC tools with the required tier's materials.
     */
    private ItemStack[] buildDisplayItems() {
        // Map vanilla tool type to TC tool registry name
        String tcToolName = mapToolTypeToTC(toolType);
        if (tcToolName == null) {
            return new ItemStack[0];
        }

        // Get the TC tool item from registry
        ResourceLocation toolId = new ResourceLocation("tconstruct", tcToolName);
        Item tool = ForgeRegistries.ITEMS.getValue(toolId);
        if (tool == null || tool == Items.AIR) {
            return new ItemStack[0];
        }

        // Check if it's a TC tool (implements IModifiable)
        if (!(tool instanceof IModifiable modifiable)) {
            return new ItemStack[0];
        }

        ToolDefinition definition = modifiable.getToolDefinition();

        // Get how many parts this tool has via ToolMaterialHook
        int partCount = ToolMaterialHook.stats(definition).size();
        if (partCount == 0) {
            return new ItemStack[0];
        }

        // Get materials for this tier
        Set<String> tierMaterials = MaterialMappingConfig.getMaterialsForTier(requiredTier);
        if (tierMaterials == null || tierMaterials.isEmpty()) {
            return new ItemStack[0];
        }

        // Use first material as the primary display material
        String primaryMaterial = tierMaterials.iterator().next();

        List<ItemStack> displayStacks = new ArrayList<>();

        // Create full-tier tool (all parts same material)
        MaterialNBT.Builder fullBuilder = MaterialNBT.builder();
        for (int i = 0; i < partCount; i++) {
            fullBuilder.add(MaterialVariantId.parse(primaryMaterial));
        }
        ToolStack fullTool = ToolStack.createTool(tool, definition, fullBuilder.build());
        displayStacks.add(fullTool.createStack());

        // Create mixed variant (tier head + wood other parts) to show flexibility
        if (partCount > 1) {
            MaterialNBT.Builder mixedBuilder = MaterialNBT.builder();
            mixedBuilder.add(MaterialVariantId.parse(primaryMaterial)); // head must match tier
            for (int i = 1; i < partCount; i++) {
                mixedBuilder.add(MaterialVariantId.parse("tconstruct:wood")); // other parts can be anything
            }
            ToolStack mixedTool = ToolStack.createTool(tool, definition, mixedBuilder.build());
            displayStacks.add(mixedTool.createStack());
        }

        return displayStacks.toArray(new ItemStack[0]);
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
