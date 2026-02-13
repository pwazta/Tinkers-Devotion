package com.pwazta.nomorevanillatools.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import com.pwazta.nomorevanillatools.Config;
import com.pwazta.nomorevanillatools.config.MaterialMappingConfig;
import com.pwazta.nomorevanillatools.config.ToolExclusionConfig;
import com.pwazta.nomorevanillatools.datagen.DatapackHelper;
import com.pwazta.nomorevanillatools.recipe.TinkerMaterialIngredient.MatchMode;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command to generate datapack recipes replacing vanilla tools with TC material checking.
 * Detects new materials from TC registry, updates configs, cleans stale recipes, and provides feedback.
 */
public class GenerateRecipesCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Vanilla tool tiers (no netherite — netherite uses SmithingRecipe, not CraftingRecipe)
    private static final String[] TIERS = {"wooden", "stone", "iron", "golden", "diamond"};
    private static final String[] TOOLS = {"sword", "pickaxe", "axe", "shovel", "hoe"};

    // Map vanilla items to their tier and type
    private static final Map<String, ToolInfo> VANILLA_TOOLS = new HashMap<>();

    // Vanilla armor tiers (no netherite — smithing; no chainmail — no recipe; no wood/stone — no vanilla armor)
    private static final String[] ARMOR_TIERS = {"leather", "iron", "golden", "diamond"};
    private static final String[] ARMOR_SLOTS = {"helmet", "chestplate", "leggings", "boots"};

    // Map vanilla armor items to their tier and slot
    private static final Map<String, ArmorInfo> VANILLA_ARMOR = new HashMap<>();

    static {
        for (String tier : TIERS) {
            for (String tool : TOOLS) {
                VANILLA_TOOLS.put("minecraft:" + tier + "_" + tool, new ToolInfo(tier, tool));
            }
        }
        for (String tier : ARMOR_TIERS) {
            for (String slot : ARMOR_SLOTS) {
                VANILLA_ARMOR.put("minecraft:" + tier + "_" + slot, new ArmorInfo(tier, slot));
            }
        }
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /** Tracks everything that happened during generation for player feedback. */
    private static class GenerationResult {
        int recipesGenerated = 0;
        int vanillaToolRecipesRemoved = 0;
        int vanillaArmorRecipesRemoved = 0;
        int staleRecipesCleaned = 0;
        int materialsAdded = 0;
        int materialsSkippedOverrides = 0;
        int armorMaterialsAdded = 0;
        int armorMaterialsSkippedOverrides = 0;
        List<String> skippedTiers = List.of();
        List<String> armorSkippedTiers = List.of();
        final List<String> errors = new ArrayList<>();
    }

    private record ToolInfo(String tier, String toolType) {}
    private record ArmorInfo(String tier, String slot) {}
    private record ReplacementInfo(int index, String tier, String toolType, MatchMode mode) {}

    // ── Command entry point ───────────────────────────────────────────────────

    /**
     * Executes the generate command. Full flow:
     * 1. Detect & merge new materials from TC registry
     * 2. Reload tool exclusions from disk
     * 3. Collect existing generated files (for stale detection)
     * 4. Generate replacement recipes
     * 5. Clean stale recipes from uninstalled mods
     * 6. Send detailed feedback
     * 7. Reload datapacks
     */
    public static int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        MinecraftServer server = context.getSource().getServer();
        CommandSourceStack source = context.getSource();

        try {
            GenerationResult result = new GenerationResult();

            // Step 1: Refresh tool materials (reload disk + merge with TC registry)
            MaterialMappingConfig.MergeResult mergeResult = MaterialMappingConfig.refreshFromRegistry();
            if (mergeResult != null) {
                result.materialsAdded = mergeResult.addedCount;
                result.materialsSkippedOverrides = mergeResult.skippedOverrides;
                result.skippedTiers = mergeResult.skippedTiers;
            } else {
                MaterialMappingConfig.reload();
            }

            // Step 1b: Refresh armor materials
            MaterialMappingConfig.MergeResult armorMerge = MaterialMappingConfig.refreshArmorFromRegistry();
            if (armorMerge != null) {
                result.armorMaterialsAdded = armorMerge.addedCount;
                result.armorMaterialsSkippedOverrides = armorMerge.skippedOverrides;
                result.armorSkippedTiers = armorMerge.skippedTiers;
            } else {
                MaterialMappingConfig.reloadArmor();
            }

            // Step 2: Reload tool/armor exclusions from disk
            ToolExclusionConfig.reload();

            // Step 3: Collect existing generated files before regenerating
            Set<Path> existingFiles = DatapackHelper.listGeneratedRecipeFiles(server);

            // Step 4: Generate all replacement recipes
            Set<Path> writtenFiles = doGenerate(server, result);

            // Step 5: Clean stale recipes (files that exist but weren't regenerated)
            for (Path existing : existingFiles) {
                if (!writtenFiles.contains(existing)) {
                    if (DatapackHelper.deleteRecipeFile(existing)) {
                        result.staleRecipesCleaned++;
                    }
                }
            }

            // Step 6: Send detailed feedback
            sendFeedback(source, result);

            // Step 7: Reload datapacks
            source.sendSuccess(() -> Component.literal("Reloading datapacks..."), false);
            server.reloadResources(server.getPackRepository().getSelectedIds()).exceptionally(e -> {
                LOGGER.error("Failed to reload datapacks after recipe generation", e);
                source.sendFailure(Component.literal("Recipes generated but datapack reload failed. Run /reload manually."));
                return null;
            }).thenRun(() -> {
                source.sendSuccess(() -> Component.literal("Datapack reload complete! Recipes are now active."), true);
            });

            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            LOGGER.error("Failed to generate recipes", e);
            source.sendFailure(Component.literal("Failed to generate recipes: " + e.getMessage()));
            return 0;
        }
    }

    // ── Auto-boot entry point (backward compat for ForgeEventHandlers) ────────

    /**
     * Generates replacement recipes. Called from ForgeEventHandlers on first server start.
     *
     * @return Total number of recipe files written (replacements + vanilla overrides)
     */
    public static int generate(MinecraftServer server) throws Exception {
        GenerationResult result = new GenerationResult();
        doGenerate(server, result);
        return result.recipesGenerated + result.vanillaToolRecipesRemoved + result.vanillaArmorRecipesRemoved;
    }

    // ── Core generation logic ─────────────────────────────────────────────────

    /**
     * Scans all loaded recipes, finds vanilla tool ingredients, and writes replacement recipes.
     *
     * @return Set of file paths written (for stale recipe detection)
     */
    private static Set<Path> doGenerate(MinecraftServer server, GenerationResult result) throws Exception {
        Set<Path> writtenFiles = new HashSet<>();
        RecipeManager recipeManager = server.getRecipeManager();
        Path datapackPath = DatapackHelper.getDatapackPath(server);
        Path dataPath = datapackPath.resolve("data");

        DatapackHelper.saveMcmeta(datapackPath);

        // Remove vanilla tool crafting recipes if configured
        if (Config.removeVanillaToolCrafting) {
            removeVanillaToolRecipes(dataPath);
            result.vanillaToolRecipesRemoved = TIERS.length * TOOLS.length;
        }

        // Remove vanilla armor crafting recipes if configured
        if (Config.removeVanillaArmorCrafting) {
            removeVanillaArmorRecipes(dataPath);
            result.vanillaArmorRecipesRemoved = ARMOR_TIERS.length * ARMOR_SLOTS.length;
        }

        // Scan all recipes and generate replacements
        for (Recipe<?> recipe : recipeManager.getRecipes()) {
            if (!(recipe instanceof CraftingRecipe)) continue;

            List<ReplacementInfo> replacements = findVanillaItems(recipe);
            if (replacements.isEmpty()) continue;

            try {
                JsonObject replacementRecipe = buildReplacementRecipe(recipe, replacements, server);
                if (replacementRecipe != null) {
                    ResourceLocation recipeId = recipe.getId();
                    String recipeName = recipeId.getPath() + "_tinker_replacement";
                    Path written = DatapackHelper.saveRecipeJson(
                        replacementRecipe, dataPath, recipeId.getNamespace(), recipeName);
                    writtenFiles.add(written);
                    result.recipesGenerated++;
                }
            } catch (Exception e) {
                ResourceLocation id = recipe.getId();
                LOGGER.warn("Skipping recipe '{}': {}", id, e.getMessage());
                result.errors.add("Skipped " + id + ": " + e.getMessage());
            }
        }

        DatapackHelper.markGenerated(server);
        LOGGER.info("Generated {} replacement recipes", result.recipesGenerated);
        return writtenFiles;
    }

    // ── Vanilla tool detection ────────────────────────────────────────────────

    private static void removeVanillaToolRecipes(Path dataPath) throws Exception {
        for (String tier : TIERS) {
            for (String tool : TOOLS) {
                DatapackHelper.removeRecipe(dataPath, "minecraft", tier + "_" + tool);
            }
        }
    }

    private static void removeVanillaArmorRecipes(Path dataPath) throws Exception {
        for (String tier : ARMOR_TIERS) {
            for (String slot : ARMOR_SLOTS) {
                DatapackHelper.removeRecipe(dataPath, "minecraft", tier + "_" + slot);
            }
        }
    }

    private static List<ReplacementInfo> findVanillaItems(Recipe<?> recipe) {
        List<ReplacementInfo> replacements = new ArrayList<>();
        NonNullList<Ingredient> ingredients = recipe.getIngredients();

        for (int i = 0; i < ingredients.size(); i++) {
            for (var stack : ingredients.get(i).getItems()) {
                ResourceLocation itemKey = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (itemKey == null) continue;
                String key = itemKey.toString();

                ToolInfo toolInfo = VANILLA_TOOLS.get(key);
                if (toolInfo != null) {
                    replacements.add(new ReplacementInfo(i, toolInfo.tier, toolInfo.toolType, MatchMode.TOOL_ACTION));
                    break;
                }

                ArmorInfo armorInfo = VANILLA_ARMOR.get(key);
                if (armorInfo != null) {
                    replacements.add(new ReplacementInfo(i, armorInfo.tier, armorInfo.slot, MatchMode.ARMOR_SLOT));
                    break;
                }
            }
        }

        return replacements;
    }

    // ── Recipe building ───────────────────────────────────────────────────────

    private static JsonObject buildReplacementRecipe(Recipe<?> recipe, List<ReplacementInfo> replacements, MinecraftServer server) {
        if (recipe instanceof ShapedRecipe shaped) {
            return buildShapedReplacement(shaped, replacements, server);
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            return buildShapelessReplacement(shapeless, replacements, server);
        }
        return null;
    }

    private static JsonObject createTinkerIngredientJson(String tier, String toolType, MatchMode mode) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "nomorevanillatools:tinker_material");
        json.addProperty("tier", tier);
        json.addProperty("tool_type", toolType);
        json.addProperty("mode", mode.name().toLowerCase());
        return json;
    }

    private static JsonObject createResultJson(Recipe<?> recipe, MinecraftServer server) {
        JsonObject result = new JsonObject();
        var resultItem = recipe.getResultItem(server.registryAccess());
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(resultItem.getItem());
        result.addProperty("item", itemId != null ? itemId.toString() : "minecraft:air");
        if (resultItem.getCount() > 1) result.addProperty("count", resultItem.getCount());
        return result;
    }

    private static JsonObject buildShapedReplacement(ShapedRecipe recipe, List<ReplacementInfo> replacements, MinecraftServer server) {
        JsonObject recipeJson = new JsonObject();
        recipeJson.addProperty("type", "minecraft:crafting_shaped");

        if (!recipe.getGroup().isEmpty()) recipeJson.addProperty("group", recipe.getGroup());
        recipeJson.addProperty("category", recipe.category().getSerializedName());

        // Build pattern and key mapping
        JsonArray pattern = new JsonArray();
        int width = recipe.getWidth();
        int height = recipe.getHeight();
        NonNullList<Ingredient> ingredients = recipe.getIngredients();

        Map<Integer, String> keyMap = new HashMap<>();
        char currentKey = 'A';

        for (int row = 0; row < height; row++) {
            StringBuilder rowPattern = new StringBuilder();
            for (int col = 0; col < width; col++) {
                int index = row * width + col;
                if (index < ingredients.size() && !ingredients.get(index).isEmpty()) {
                    keyMap.put(index, String.valueOf(currentKey));
                    rowPattern.append(currentKey);
                    currentKey++;
                } else {
                    rowPattern.append(' ');
                }
            }
            pattern.add(rowPattern.toString());
        }
        recipeJson.add("pattern", pattern);

        // Build key with replacements
        JsonObject key = new JsonObject();
        for (Map.Entry<Integer, String> entry : keyMap.entrySet()) {
            int index = entry.getKey();
            String keyChar = entry.getValue();

            ReplacementInfo replacement = findReplacementForIndex(replacements, index);
            if (replacement != null) {
                key.add(keyChar, createTinkerIngredientJson(replacement.tier, replacement.toolType, replacement.mode));
            } else {
                key.add(keyChar, ingredients.get(index).toJson());
            }
        }
        recipeJson.add("key", key);

        recipeJson.add("result", createResultJson(recipe, server));
        return recipeJson;
    }

    private static JsonObject buildShapelessReplacement(ShapelessRecipe recipe, List<ReplacementInfo> replacements, MinecraftServer server) {
        JsonObject recipeJson = new JsonObject();
        recipeJson.addProperty("type", "minecraft:crafting_shapeless");

        if (!recipe.getGroup().isEmpty()) recipeJson.addProperty("group", recipe.getGroup());
        recipeJson.addProperty("category", recipe.category().getSerializedName());

        JsonArray ingredientsArray = new JsonArray();
        NonNullList<Ingredient> ingredients = recipe.getIngredients();

        for (int i = 0; i < ingredients.size(); i++) {
            ReplacementInfo replacement = findReplacementForIndex(replacements, i);
            if (replacement != null) {
                ingredientsArray.add(createTinkerIngredientJson(replacement.tier, replacement.toolType, replacement.mode));
            } else {
                ingredientsArray.add(ingredients.get(i).toJson());
            }
        }
        recipeJson.add("ingredients", ingredientsArray);

        recipeJson.add("result", createResultJson(recipe, server));
        return recipeJson;
    }

    /** Finds the replacement for a given ingredient index, or null. */
    private static ReplacementInfo findReplacementForIndex(List<ReplacementInfo> replacements, int index) {
        for (ReplacementInfo r : replacements) {
            if (r.index == index) return r;
        }
        return null;
    }

    // ── Player feedback ───────────────────────────────────────────────────────

    private static void sendFeedback(CommandSourceStack source, GenerationResult result) {
        source.sendSuccess(() -> Component.literal("=== Recipe Generation Complete ==="), true);

        // Tool materials
        if (result.materialsAdded > 0) {
            source.sendSuccess(() -> Component.literal(
                "  Tool materials: " + result.materialsAdded + " new materials detected and added"), false);
        }
        if (result.materialsSkippedOverrides > 0) {
            source.sendSuccess(() -> Component.literal(
                "  Tool materials: " + result.materialsSkippedOverrides + " kept in user-assigned tiers"), false);
        }
        if (!result.skippedTiers.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                "  Warning: " + result.skippedTiers.size() + " tool materials have unsupported modded tiers (skipped)"), false);
            for (String skipped : result.skippedTiers) {
                source.sendSuccess(() -> Component.literal("    - " + skipped), false);
            }
        }

        // Armor materials
        if (result.armorMaterialsAdded > 0) {
            source.sendSuccess(() -> Component.literal(
                "  Armor materials: " + result.armorMaterialsAdded + " new plating materials detected and added"), false);
        }
        if (result.armorMaterialsSkippedOverrides > 0) {
            source.sendSuccess(() -> Component.literal(
                "  Armor materials: " + result.armorMaterialsSkippedOverrides + " kept in user-assigned tiers"), false);
        }
        if (!result.armorSkippedTiers.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                "  Warning: " + result.armorSkippedTiers.size() + " armor materials have unsupported tiers (skipped)"), false);
            for (String skipped : result.armorSkippedTiers) {
                source.sendSuccess(() -> Component.literal("    - " + skipped), false);
            }
        }

        // Recipes
        source.sendSuccess(() -> Component.literal(
            "  Recipes: " + result.recipesGenerated + " replacement recipes generated"), false);

        if (result.vanillaToolRecipesRemoved > 0) {
            source.sendSuccess(() -> Component.literal(
                "  Recipes: " + result.vanillaToolRecipesRemoved + " vanilla tool recipes disabled"), false);
        }
        if (result.vanillaArmorRecipesRemoved > 0) {
            source.sendSuccess(() -> Component.literal(
                "  Recipes: " + result.vanillaArmorRecipesRemoved + " vanilla armor recipes disabled"), false);
        }

        if (result.staleRecipesCleaned > 0) {
            source.sendSuccess(() -> Component.literal(
                "  Cleanup: " + result.staleRecipesCleaned + " stale recipes removed (from uninstalled mods)"), false);
        }

        for (String error : result.errors) {
            source.sendFailure(Component.literal("  Error: " + error));
        }
    }
}
