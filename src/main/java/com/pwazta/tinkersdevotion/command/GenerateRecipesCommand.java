package com.pwazta.tinkersdevotion.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import com.pwazta.tinkersdevotion.Config;
import com.pwazta.tinkersdevotion.config.ModifierSkipListConfig;
import com.pwazta.tinkersdevotion.config.TiersToTcMaterials;
import com.pwazta.tinkersdevotion.config.ToolExclusionConfig;
import com.pwazta.tinkersdevotion.datagen.DatapackHelper;
import com.pwazta.tinkersdevotion.loot.VanillaItemMappings;
import com.pwazta.tinkersdevotion.recipe.ArmorMode;
import com.pwazta.tinkersdevotion.recipe.FishingRodMode;
import com.pwazta.tinkersdevotion.recipe.FlintAndSteelMode;
import com.pwazta.tinkersdevotion.recipe.IngredientMode;
import com.pwazta.tinkersdevotion.recipe.RangedMode;
import com.pwazta.tinkersdevotion.recipe.ShieldMode;
import com.pwazta.tinkersdevotion.recipe.ToolMode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
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
        int vanillaRecipesRemoved = 0;
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

            // Step 4b: Disable vanilla output recipes via Mantle (writes to its own datapack)
            result.vanillaRecipesRemoved = disableVanillaRecipes(server);

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
            DatapackHelper.discoverAndReload(server).exceptionally(e -> {
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
        return result.recipesGenerated + result.vanillaRecipesRemoved;
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

        // Scan all recipes and generate replacements
        for (Recipe<?> recipe : recipeManager.getRecipes()) {
            List<Ingredient> ingredients = extractScannableIngredients(recipe);
            if (ingredients == null) continue;

            List<ReplacementEntry> replacements = findVanillaItems(ingredients);
            if (replacements.isEmpty()) continue;

            // Vanilla output: Mantle disables the original, don't add a replacement path.
            ItemStack out = recipe.getResultItem(server.registryAccess());
            ResourceLocation outId = ForgeRegistries.ITEMS.getKey(out.getItem());
            if (outId != null && VanillaItemMappings.getReplacementInfoById(outId.toString()) != null) continue;

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

    // ── Mantle delegation ─────────────────────────────────────────────────────

    /**
     * Disables vanilla crafting via Mantle's {@code vanilla_tools} + {@code netherite_smithing} presets
     * (tag-based, inverted vs {@code tconstruct:modifiable}, catches modded recipes producing vanilla items).
     * Output goes to Mantle's {@code SlimeKnightsGenerated} datapack — caller's reload picks it up.
     * Must be called after the overworld is loaded ({@code ServerStartedEvent} or later) — Mantle reads {@code source.getLevel()}.
     */
    public static int disableVanillaRecipes(MinecraftServer server) {
        if (!Config.disableVanillaCrafting) return 0;
        CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
        int count = 0;
        for (String preset : List.of("tconstruct:vanilla_tools", "tconstruct:netherite_smithing")) {
            try {
                count += server.getCommands().getDispatcher()
                    .execute("mantle remove recipes preset " + preset, source);
            } catch (CommandSyntaxException e) {
                LOGGER.error("Failed to dispatch Mantle preset '{}' — TC dependency missing or broken?", preset, e);
            }
        }
        return count;
    }

    // ── Recipe scanning ───────────────────────────────────────────────────────

    /**
     * Returns the list of ingredients to scan for a supported recipe type, or null for unsupported types.
     * {@code SmithingRecipe} does not override {@code getIngredients()} (default returns empty),
     * so smithing recipes need explicit per-slot extraction via AT-exposed fields.
     */
    private static @Nullable List<Ingredient> extractScannableIngredients(Recipe<?> recipe) {
        if (recipe instanceof CraftingRecipe) {
            return recipe.getIngredients();
        }
        if (recipe instanceof SmithingTransformRecipe t) {
            return List.of(t.template, t.base, t.addition);
        }
        // SmithingTrimRecipe intentionally skipped — base is always the `minecraft:trimmable_armor` tag,
        // which the tag-safety guard below would reject anyway. TC armor is not in that tag.
        return null;
    }

    /**
     * Scans an ingredient list and returns replacement entries for slots holding a single tracked vanilla item.
     *
     * <p>Tag safety: only single-item ingredients ({@code getItems().length == 1}) are candidates for replacement.
     * Tag-based or compound ingredients (e.g. {@code forge:tools/bows}, {@code minecraft:trimmable_armor}) are
     * preserved verbatim — replacing them would narrow their semantics and silently drop accepted items.
     */
    private static List<ReplacementEntry> findVanillaItems(List<Ingredient> ingredients) {
        List<ReplacementEntry> replacements = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            ItemStack[] stacks = ingredients.get(i).getItems();
            if (stacks.length != 1) continue;
            ResourceLocation itemKey = ForgeRegistries.ITEMS.getKey(stacks[0].getItem());
            if (itemKey == null) continue;
            VanillaItemMappings.ReplacementInfo info = VanillaItemMappings.getReplacementInfoById(itemKey.toString());
            if (info != null) replacements.add(new ReplacementEntry(i, toIngredientMode(info)));
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
        } else if (info instanceof VanillaItemMappings.ShieldInfo s) {
            return new ShieldMode(s.shieldType(), s.canonicalMaterials());
        } else if (info instanceof VanillaItemMappings.FishingRodInfo f) {
            return new FishingRodMode(f.fishingRodType(), f.canonicalMaterials());
        } else if (info instanceof VanillaItemMappings.FlintAndSteelInfo fs) {
            return new FlintAndSteelMode(fs.flintAndSteelType(), fs.tcItemIds());
        }
        throw new IllegalStateException("Unknown ReplacementInfo type: " + info.getClass());
    }

    // ── Recipe building ───────────────────────────────────────────────────────

    private static JsonObject buildReplacementRecipe(Recipe<?> recipe, List<ReplacementEntry> replacements, MinecraftServer server) {
        if (recipe instanceof ShapedRecipe shaped) {
            return buildShapedReplacement(shaped, replacements, server);
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            return buildShapelessReplacement(shapeless, replacements, server);
        } else if (recipe instanceof SmithingTransformRecipe transform) {
            return buildSmithingTransformReplacement(transform, replacements, server);
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

        int width = recipe.getWidth();
        int height = recipe.getHeight();
        NonNullList<Ingredient> ingredients = recipe.getIngredients();

        // Dedupe by serialized ingredient JSON: identical slots share one key char.
        JsonArray pattern = new JsonArray();
        JsonObject key = new JsonObject();
        Map<String, String> keyByJson = new HashMap<>();
        char nextKey = 'A';

        for (int row = 0; row < height; row++) {
            StringBuilder rowPattern = new StringBuilder();
            for (int col = 0; col < width; col++) {
                int index = row * width + col;
                if (index >= ingredients.size() || ingredients.get(index).isEmpty()) {
                    rowPattern.append(' ');
                    continue;
                }
                JsonElement ingJson = ingredientJsonFor(ingredients.get(index), index, replacements);
                String jsonStr = ingJson.toString();
                String keyChar = keyByJson.get(jsonStr);
                if (keyChar == null) {
                    keyChar = String.valueOf(nextKey++);
                    keyByJson.put(jsonStr, keyChar);
                    key.add(keyChar, ingJson);
                }
                rowPattern.append(keyChar);
            }
            pattern.add(rowPattern.toString());
        }

        recipeJson.add("pattern", pattern);
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
            ingredientsArray.add(ingredientJsonFor(ingredients.get(i), i, replacements));
        }
        recipeJson.add("ingredients", ingredientsArray);

        recipeJson.add("result", createResultJson(recipe, server));
        return recipeJson;
    }

    /**
     * Smithing transform has three named slots (template, base, addition) matching the scan order
     * emitted by {@link #extractScannableIngredients}. Per-slot replacement preserves template/addition
     * when not tracked (e.g. modded upgrade templates).
     */
    private static JsonObject buildSmithingTransformReplacement(SmithingTransformRecipe recipe, List<ReplacementEntry> replacements, MinecraftServer server) {
        JsonObject recipeJson = new JsonObject();
        recipeJson.addProperty("type", "minecraft:smithing_transform");

        recipeJson.add("template", ingredientJsonFor(recipe.template, 0, replacements));
        recipeJson.add("base",     ingredientJsonFor(recipe.base,     1, replacements));
        recipeJson.add("addition", ingredientJsonFor(recipe.addition, 2, replacements));

        recipeJson.add("result", createResultJson(recipe, server));
        return recipeJson;
    }

    /** Emits the replacement ingredient if this index was tracked; otherwise preserves the original. */
    private static JsonElement ingredientJsonFor(Ingredient original, int index, List<ReplacementEntry> replacements) {
        ReplacementEntry replacement = findReplacementForIndex(replacements, index);
        return replacement != null ? createTinkerIngredientJson(replacement) : original.toJson();
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

        if (result.vanillaRecipesRemoved > 0) {
            source.sendSuccess(() -> Component.literal(
                "  Recipes: " + result.vanillaRecipesRemoved + " vanilla recipes disabled (via Mantle)"), false);
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
