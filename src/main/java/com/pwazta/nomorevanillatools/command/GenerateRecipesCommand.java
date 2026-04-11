package com.pwazta.nomorevanillatools.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import com.pwazta.nomorevanillatools.Config;
import com.pwazta.nomorevanillatools.config.ModifierSkipListConfig;
import com.pwazta.nomorevanillatools.config.TiersToTcMaterials;
import com.pwazta.nomorevanillatools.config.ToolExclusionConfig;
import com.pwazta.nomorevanillatools.datagen.DatapackHelper;
import com.pwazta.nomorevanillatools.loot.VanillaItemMappings;
import com.pwazta.nomorevanillatools.recipe.ArmorMode;
import com.pwazta.nomorevanillatools.recipe.IngredientMode;
import com.pwazta.nomorevanillatools.recipe.RangedMode;
import com.pwazta.nomorevanillatools.recipe.ToolMode;
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
 * Command to generate datapack recipes replacing vanilla tool/armor/ranged ingredients with TC equivalents.
 * Reloads exclusion configs, scans loaded crafting recipes, writes replacements, cleans stale files, and triggers a datapack reload.
 */
public class GenerateRecipesCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ── Inner types ───────────────────────────────────────────────────────────

    /** Tracks everything that happened during generation for player feedback. */
    private static class GenerationResult {
        int recipesGenerated = 0;
        int vanillaToolRecipesRemoved = 0;
        int vanillaArmorRecipesRemoved = 0;
        int vanillaRangedRecipesRemoved = 0;
        int staleRecipesCleaned = 0;
        List<String> unmappedToolMaterials = List.of();
        final List<String> errors = new ArrayList<>();
    }

    /** Info about a vanilla ingredient to replace with a TC ingredient. */
    private record ReplacementEntry(int index, IngredientMode mode) {}

    // ── Command entry point ───────────────────────────────────────────────────

    /**
     * Executes the generate command. Full flow:
     * 1. Capture any tool materials skipped during the last cache rebuild (addon-pack feedback)
     * 2. Reload exclusion + modifier skip list configs from disk
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

            // Step 1: Capture tool materials dropped from the last cache rebuild because their
            // vanilla Tier is unmappable. Diagnostic feedback for addon-pack authors — not user
            // exclusions. The cache itself is rebuilt on MaterialsLoadedEvent, not here.
            result.unmappedToolMaterials = TiersToTcMaterials.getUnmappedToolMaterials();

            // Step 2: Reload tool/armor exclusions and modifier skip list from disk
            ToolExclusionConfig.reload();
            ModifierSkipListConfig.reload();

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

    // ── Reset subcommand ──────────────────────────────────────────────────────

    /**
     * Resets all disk-backed configs to defaults and regenerates everything from scratch.
     * Tool tier state lives in the TC registry (no file to delete).
     */
    public static int runReset(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.literal("Resetting all configs to defaults..."), true);

        // Reset tool exclusions and modifier skip list to defaults.
        // Tool tier state lives in the TC registry — no config file to delete.
        ToolExclusionConfig.resetToDefaults();
        ModifierSkipListConfig.resetToDefaults();

        source.sendSuccess(() -> Component.literal("Configs reset. Regenerating..."), false);
        return run(context);
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
        return result.recipesGenerated + result.vanillaToolRecipesRemoved + result.vanillaArmorRecipesRemoved + result.vanillaRangedRecipesRemoved;
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
            result.vanillaToolRecipesRemoved = VanillaItemMappings.RECIPE_TOOL_TIERS.length * VanillaItemMappings.TOOL_TYPES.length;
        }

        // Remove vanilla armor crafting recipes if configured
        if (Config.removeVanillaArmorCrafting) {
            removeVanillaArmorRecipes(dataPath);
            result.vanillaArmorRecipesRemoved = VanillaItemMappings.RECIPE_ARMOR_TIERS.length * VanillaItemMappings.ARMOR_SLOTS.length;
        }

        // Remove vanilla ranged weapon crafting recipes if configured
        if (Config.removeVanillaRangedCrafting) {
            removeVanillaRangedRecipes(dataPath);
            result.vanillaRangedRecipesRemoved = VanillaItemMappings.RANGED_TYPES.length;
        }

        // Scan all recipes and generate replacements
        for (Recipe<?> recipe : recipeManager.getRecipes()) {
            if (!(recipe instanceof CraftingRecipe)) continue;

            List<ReplacementEntry> replacements = findVanillaItems(recipe);
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
        for (String tier : VanillaItemMappings.RECIPE_TOOL_TIERS) {
            for (String tool : VanillaItemMappings.TOOL_TYPES) {
                DatapackHelper.removeRecipe(dataPath, "minecraft", tier + "_" + tool);
            }
        }
    }

    private static void removeVanillaArmorRecipes(Path dataPath) throws Exception {
        for (String tier : VanillaItemMappings.RECIPE_ARMOR_TIERS) {
            for (String slot : VanillaItemMappings.ARMOR_SLOTS) {
                DatapackHelper.removeRecipe(dataPath, "minecraft", tier + "_" + slot);
            }
        }
    }

    private static void removeVanillaRangedRecipes(Path dataPath) throws Exception {
        for (String type : VanillaItemMappings.RANGED_TYPES) {
            DatapackHelper.removeRecipe(dataPath, "minecraft", type);
        }
    }

    private static List<ReplacementEntry> findVanillaItems(Recipe<?> recipe) {
        List<ReplacementEntry> replacements = new ArrayList<>();
        NonNullList<Ingredient> ingredients = recipe.getIngredients();

        for (int i = 0; i < ingredients.size(); i++) {
            for (var stack : ingredients.get(i).getItems()) {
                ResourceLocation itemKey = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (itemKey == null) continue;

                VanillaItemMappings.ReplacementInfo info = VanillaItemMappings.getReplacementInfoById(itemKey.toString());
                if (info != null) {
                    replacements.add(new ReplacementEntry(i, toIngredientMode(info)));
                    break;
                }
            }
        }

        return replacements;
    }

    /** Bridges loot-side ReplacementInfo to recipe-side IngredientMode. */
    private static IngredientMode toIngredientMode(VanillaItemMappings.ReplacementInfo info) {
        if (info instanceof VanillaItemMappings.ToolInfo t) {
            return new ToolMode(t.tier(), t.toolType());
        } else if (info instanceof VanillaItemMappings.ArmorInfo a) {
            return new ArmorMode(a.slot(), null, a.minTier(), a.maxTier());
        } else if (info instanceof VanillaItemMappings.RangedInfo r) {
            return new RangedMode(r.rangedType(), r.canonicalMaterials());
        }
        throw new IllegalStateException("Unknown ReplacementInfo type: " + info.getClass());
    }

    // ── Recipe building ───────────────────────────────────────────────────────

    private static JsonObject buildReplacementRecipe(Recipe<?> recipe, List<ReplacementEntry> replacements, MinecraftServer server) {
        if (recipe instanceof ShapedRecipe shaped) {
            return buildShapedReplacement(shaped, replacements, server);
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            return buildShapelessReplacement(shapeless, replacements, server);
        }
        return null;
    }

    private static JsonObject createTinkerIngredientJson(ReplacementEntry entry) {
        return (JsonObject) entry.mode().toJson();
    }

    private static JsonObject createResultJson(Recipe<?> recipe, MinecraftServer server) {
        JsonObject result = new JsonObject();
        var resultItem = recipe.getResultItem(server.registryAccess());
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(resultItem.getItem());
        result.addProperty("item", itemId != null ? itemId.toString() : "minecraft:air");
        if (resultItem.getCount() > 1) result.addProperty("count", resultItem.getCount());
        return result;
    }

    private static JsonObject buildShapedReplacement(ShapedRecipe recipe, List<ReplacementEntry> replacements, MinecraftServer server) {
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

            ReplacementEntry replacement = findReplacementForIndex(replacements, index);
            if (replacement != null) {
                key.add(keyChar, createTinkerIngredientJson(replacement));
            } else {
                key.add(keyChar, ingredients.get(index).toJson());
            }
        }
        recipeJson.add("key", key);

        recipeJson.add("result", createResultJson(recipe, server));
        return recipeJson;
    }

    private static JsonObject buildShapelessReplacement(ShapelessRecipe recipe, List<ReplacementEntry> replacements, MinecraftServer server) {
        JsonObject recipeJson = new JsonObject();
        recipeJson.addProperty("type", "minecraft:crafting_shapeless");

        if (!recipe.getGroup().isEmpty()) recipeJson.addProperty("group", recipe.getGroup());
        recipeJson.addProperty("category", recipe.category().getSerializedName());

        JsonArray ingredientsArray = new JsonArray();
        NonNullList<Ingredient> ingredients = recipe.getIngredients();

        for (int i = 0; i < ingredients.size(); i++) {
            ReplacementEntry replacement = findReplacementForIndex(replacements, i);
            if (replacement != null) {
                ingredientsArray.add(createTinkerIngredientJson(replacement));
            } else {
                ingredientsArray.add(ingredients.get(i).toJson());
            }
        }
        recipeJson.add("ingredients", ingredientsArray);

        recipeJson.add("result", createResultJson(recipe, server));
        return recipeJson;
    }

    /** Finds the replacement for a given ingredient index, or null. */
    private static ReplacementEntry findReplacementForIndex(List<ReplacementEntry> replacements, int index) {
        for (ReplacementEntry r : replacements) {
            if (r.index == index) return r;
        }
        return null;
    }

    // ── Player feedback ───────────────────────────────────────────────────────

    private static void sendFeedback(CommandSourceStack source, GenerationResult result) {
        source.sendSuccess(() -> Component.literal("=== Recipe Generation Complete ==="), true);

        if (!result.unmappedToolMaterials.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                "  Warning: " + result.unmappedToolMaterials.size() + " tool materials have unmapped modded tiers (dropped from pool)"), false);
            for (String unmapped : result.unmappedToolMaterials) {
                source.sendSuccess(() -> Component.literal("    - " + unmapped), false);
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
        if (result.vanillaRangedRecipesRemoved > 0) {
            source.sendSuccess(() -> Component.literal(
                "  Recipes: " + result.vanillaRangedRecipesRemoved + " vanilla ranged recipes disabled"), false);
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
