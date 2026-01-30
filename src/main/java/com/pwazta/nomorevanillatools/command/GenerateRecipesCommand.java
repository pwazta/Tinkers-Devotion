package com.pwazta.nomorevanillatools.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import com.pwazta.nomorevanillatools.Config;
import com.pwazta.nomorevanillatools.datagen.DatapackHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command to generate datapack recipes replacing vanilla tools with TC material checking.
 */
public class GenerateRecipesCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Vanilla tool tiers
    private static final String[] TIERS = {"wooden", "stone", "iron", "golden", "diamond"};
    // Vanilla tool types
    private static final String[] TOOLS = {"sword", "pickaxe", "axe", "shovel", "hoe"};

    // Map vanilla items to their tier and type
    private static final Map<String, ToolInfo> VANILLA_TOOLS = new HashMap<>();

    static {
        // Initialize vanilla tool mappings
        for (String tier : TIERS) {
            for (String tool : TOOLS) {
                String itemName = tier + "_" + tool;
                VANILLA_TOOLS.put("minecraft:" + itemName, new ToolInfo(tier, tool));
            }
        }
    }

    /**
     * Executes the generate command.
     *
     * @param context The command context
     * @return Command result
     * @throws CommandSyntaxException If command execution fails
     */
    public static int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        MinecraftServer server = context.getSource().getServer();
        CommandSourceStack source = context.getSource();

        try {
            int count = generate(server);
            source.sendSuccess(() -> Component.literal("Successfully generated " + count + " replacement recipes!"), true);
            source.sendSuccess(() -> Component.literal("Recipes saved to: world/datapacks/nomorevanillatools_generated/"), false);
            source.sendSuccess(() -> Component.literal("Run /reload to apply the changes."), false);
            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            LOGGER.error("Failed to generate recipes", e);
            source.sendFailure(Component.literal("Failed to generate recipes: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Generates replacement recipes for all recipes using vanilla tools.
     *
     * @param server The MinecraftServer instance
     * @return Number of recipes generated
     * @throws Exception If generation fails
     */
    public static int generate(MinecraftServer server) throws Exception {
        RecipeManager recipeManager = server.getRecipeManager();
        Path datapackPath = DatapackHelper.getDatapackPath(server);
        Path dataPath = datapackPath.resolve("data");

        // Create pack.mcmeta
        DatapackHelper.saveMcmeta(datapackPath);

        int count = 0;

        // Remove vanilla tool crafting recipes if configured
        if (Config.removeVanillaToolCrafting) {
            removeVanillaToolRecipes(dataPath);
            count += TIERS.length * TOOLS.length; // 25 recipes
        }

        // Scan all recipes and generate replacements
        for (Recipe<?> recipe : recipeManager.getRecipes()) {
            if (!(recipe instanceof CraftingRecipe)) {
                continue; // Only process crafting recipes
            }

            // Check if recipe uses vanilla tools
            List<ReplacementInfo> replacements = findVanillaTools(recipe);
            if (replacements.isEmpty()) {
                continue; // No vanilla tools found
            }

            // Generate replacement recipe
            JsonObject replacementRecipe = buildReplacementRecipe(recipe, replacements);
            if (replacementRecipe != null) {
                ResourceLocation recipeId = recipe.getId();
                String recipeName = recipeId.getPath() + "_tinker_replacement";
                DatapackHelper.saveRecipeJson(replacementRecipe, dataPath, recipeId.getNamespace(), recipeName);
                count++;
            }
        }

        // Mark as generated
        DatapackHelper.markGenerated(server);

        LOGGER.info("Generated {} replacement recipes", count);
        return count;
    }

    /**
     * Removes vanilla tool crafting recipes by creating empty overrides.
     *
     * @param dataPath The data folder path
     * @throws Exception If removal fails
     */
    private static void removeVanillaToolRecipes(Path dataPath) throws Exception {
        for (String tier : TIERS) {
            for (String tool : TOOLS) {
                String recipeName = tier + "_" + tool;
                DatapackHelper.removeRecipe(dataPath, "minecraft", recipeName);
            }
        }
    }

    /**
     * Finds vanilla tools in a recipe's ingredients.
     *
     * @param recipe The recipe to check
     * @return List of replacement information for vanilla tools found
     */
    private static List<ReplacementInfo> findVanillaTools(Recipe<?> recipe) {
        List<ReplacementInfo> replacements = new ArrayList<>();
        NonNullList<Ingredient> ingredients = recipe.getIngredients();

        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);

            // Check each item in the ingredient
            for (var stack : ingredient.getItems()) {
                // Get the registry name of the item (e.g., "minecraft:iron_sword")
                ResourceLocation itemKey = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (itemKey == null) continue;

                String itemId = itemKey.toString();

                // Check if it's a vanilla tool
                if (VANILLA_TOOLS.containsKey(itemId)) {
                    ToolInfo toolInfo = VANILLA_TOOLS.get(itemId);
                    replacements.add(new ReplacementInfo(i, toolInfo.tier, toolInfo.toolType));
                    break; // Only need one match per ingredient
                }
            }
        }

        return replacements;
    }

    /**
     * Builds a replacement recipe JSON with TinkerMaterialIngredient.
     *
     * @param recipe The original recipe
     * @param replacements The vanilla tools to replace
     * @return The replacement recipe JSON
     */
    private static JsonObject buildReplacementRecipe(Recipe<?> recipe, List<ReplacementInfo> replacements) {
        if (recipe instanceof ShapedRecipe shaped) {
            return buildShapedReplacement(shaped, replacements);
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            return buildShapelessReplacement(shapeless, replacements);
        }
        return null;
    }

    /**
     * Creates a TinkerMaterialIngredient JSON object.
     *
     * @param tier The material tier
     * @param toolType The tool type
     * @return The ingredient JSON
     */
    private static JsonObject createTinkerIngredientJson(String tier, String toolType) {
        JsonObject ingredientJson = new JsonObject();
        ingredientJson.addProperty("type", "nomorevanillatools:tinker_material");
        ingredientJson.addProperty("tier", tier);
        ingredientJson.addProperty("tool_type", toolType);
        return ingredientJson;
    }

    /**
     * Creates a result JSON object from a recipe.
     *
     * @param recipe The recipe
     * @return The result JSON
     */
    private static JsonObject createResultJson(Recipe<?> recipe) {
        JsonObject result = new JsonObject();
        var resultItem = recipe.getResultItem(null);
        result.addProperty("item", resultItem.getItem().toString());
        int count = resultItem.getCount();
        if (count > 1) {
            result.addProperty("count", count);
        }
        return result;
    }

    /**
     * Builds a shaped recipe replacement.
     *
     * @param recipe The original shaped recipe
     * @param replacements The vanilla tools to replace
     * @return The replacement recipe JSON
     */
    private static JsonObject buildShapedReplacement(ShapedRecipe recipe, List<ReplacementInfo> replacements) {
        JsonObject recipeJson = new JsonObject();
        recipeJson.addProperty("type", "minecraft:crafting_shaped");

        // Add group if present
        if (!recipe.getGroup().isEmpty()) {
            recipeJson.addProperty("group", recipe.getGroup());
        }

        // Add category
        recipeJson.addProperty("category", recipe.category().getSerializedName());

        // Build pattern
        JsonArray pattern = new JsonArray();
        int width = recipe.getWidth();
        int height = recipe.getHeight();
        NonNullList<Ingredient> ingredients = recipe.getIngredients();

        // Create pattern and key mapping
        Map<Integer, String> keyMap = new HashMap<>();
        char currentKey = 'A';

        for (int row = 0; row < height; row++) {
            StringBuilder rowPattern = new StringBuilder();
            for (int col = 0; col < width; col++) {
                int index = row * width + col;
                if (index < ingredients.size() && !ingredients.get(index).isEmpty()) {
                    String key = String.valueOf(currentKey);
                    keyMap.put(index, key);
                    rowPattern.append(currentKey);
                    currentKey++;
                } else {
                    rowPattern.append(' ');
                }
            }
            pattern.add(rowPattern.toString());
        }
        recipeJson.add("pattern", pattern);

        // Build key
        JsonObject key = new JsonObject();
        for (Map.Entry<Integer, String> entry : keyMap.entrySet()) {
            int index = entry.getKey();
            String keyChar = entry.getValue();

            // Check if this ingredient should be replaced
            ReplacementInfo replacement = replacements.stream()
                .filter(r -> r.index == index)
                .findFirst()
                .orElse(null);

            if (replacement != null) {
                // Use TinkerMaterialIngredient
                key.add(keyChar, createTinkerIngredientJson(replacement.tier, replacement.toolType));
            } else {
                // Use original ingredient
                key.add(keyChar, ingredients.get(index).toJson());
            }
        }
        recipeJson.add("key", key);

        // Add result
        recipeJson.add("result", createResultJson(recipe));

        return recipeJson;
    }

    /**
     * Builds a shapeless recipe replacement.
     *
     * @param recipe The original shapeless recipe
     * @param replacements The vanilla tools to replace
     * @return The replacement recipe JSON
     */
    private static JsonObject buildShapelessReplacement(ShapelessRecipe recipe, List<ReplacementInfo> replacements) {
        JsonObject recipeJson = new JsonObject();
        recipeJson.addProperty("type", "minecraft:crafting_shapeless");

        // Add group if present
        if (!recipe.getGroup().isEmpty()) {
            recipeJson.addProperty("group", recipe.getGroup());
        }

        // Add category
        recipeJson.addProperty("category", recipe.category().getSerializedName());

        // Build ingredients
        JsonArray ingredientsArray = new JsonArray();
        NonNullList<Ingredient> ingredients = recipe.getIngredients();

        for (int i = 0; i < ingredients.size(); i++) {
            // Check if this ingredient should be replaced
            int finalI = i;
            ReplacementInfo replacement = replacements.stream()
                .filter(r -> r.index == finalI)
                .findFirst()
                .orElse(null);

            if (replacement != null) {
                // Use TinkerMaterialIngredient
                ingredientsArray.add(createTinkerIngredientJson(replacement.tier, replacement.toolType));
            } else {
                // Use original ingredient
                ingredientsArray.add(ingredients.get(i).toJson());
            }
        }
        recipeJson.add("ingredients", ingredientsArray);

        // Add result
        recipeJson.add("result", createResultJson(recipe));

        return recipeJson;
    }

    /**
     * Helper class to store tool tier and type information.
     */
    private static class ToolInfo {
        final String tier;
        final String toolType;

        ToolInfo(String tier, String toolType) {
            this.tier = tier;
            this.toolType = toolType;
        }
    }

    /**
     * Helper class to store replacement information.
     */
    private static class ReplacementInfo {
        final int index;
        final String tier;
        final String toolType;

        ReplacementInfo(int index, String tier, String toolType) {
            this.index = index;
            this.tier = tier;
            this.toolType = toolType;
        }
    }
}
