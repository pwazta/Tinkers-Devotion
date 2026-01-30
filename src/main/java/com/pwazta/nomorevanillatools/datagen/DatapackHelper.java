package com.pwazta.nomorevanillatools.datagen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Helper class for managing datapack generation.
 * Handles folder creation, pack.mcmeta generation, and recipe JSON saving.
 */
public class DatapackHelper {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DATAPACK_NAME = "nomorevanillatools_generated";

    /**
     * Gets the path to the generated datapack folder.
     *
     * @param server The MinecraftServer instance
     * @return Path to the datapack folder
     */
    public static Path getDatapackPath(MinecraftServer server) {
        Path worldDir = server.getWorldPath(LevelResource.ROOT);
        return worldDir.resolve("datapacks").resolve(DATAPACK_NAME);
    }

    /**
     * Creates the pack.mcmeta file for the datapack.
     *
     * @param packPath The path to the datapack folder
     * @throws IOException If file creation fails
     */
    public static void saveMcmeta(Path packPath) throws IOException {
        JsonObject packMcmeta = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", 15); // Minecraft 1.20.1 uses pack format 15
        pack.addProperty("description", "Generated recipes for No More Vanilla Tools");
        packMcmeta.add("pack", pack);

        Path mcmetaPath = packPath.resolve("pack.mcmeta");
        Files.createDirectories(packPath);
        Files.writeString(mcmetaPath, GSON.toJson(packMcmeta));
    }

    /**
     * Saves a recipe JSON to the datapack.
     *
     * @param recipe The recipe JSON object
     * @param dataPath The data folder path (e.g., datapack/data/)
     * @param namespace The recipe namespace (e.g., "minecraft", "modid")
     * @param recipeName The recipe name (without .json extension)
     * @throws IOException If file creation fails
     */
    public static void saveRecipeJson(JsonObject recipe, Path dataPath, String namespace, String recipeName) throws IOException {
        Path recipesDir = dataPath.resolve(namespace).resolve("recipes");
        Files.createDirectories(recipesDir);

        Path recipeFile = recipesDir.resolve(recipeName + ".json");
        Files.writeString(recipeFile, GSON.toJson(recipe));
    }

    /**
     * Creates an empty recipe JSON to override/remove a recipe.
     *
     * @param dataPath The data folder path
     * @param namespace The recipe namespace
     * @param recipeName The recipe name to remove
     * @throws IOException If file creation fails
     */
    public static void removeRecipe(Path dataPath, String namespace, String recipeName) throws IOException {
        Path recipesDir = dataPath.resolve(namespace).resolve("recipes");
        Files.createDirectories(recipesDir);

        // Create empty JSON object to override the recipe
        Path recipeFile = recipesDir.resolve(recipeName + ".json");
        Files.writeString(recipeFile, "{}");
    }

    /**
     * Checks if the datapack has already been generated.
     *
     * @param server The MinecraftServer instance
     * @return true if the .generated flag file exists
     */
    public static boolean isGenerated(MinecraftServer server) {
        Path flagFile = getDatapackPath(server).resolve(".generated");
        return Files.exists(flagFile);
    }

    /**
     * Creates the .generated flag file.
     *
     * @param server The MinecraftServer instance
     * @throws IOException If file creation fails
     */
    public static void markGenerated(MinecraftServer server) throws IOException {
        Path flagFile = getDatapackPath(server).resolve(".generated");
        Files.createDirectories(flagFile.getParent());
        Files.writeString(flagFile, "This file indicates that recipes have been generated. Delete to force regeneration.");
    }
}
