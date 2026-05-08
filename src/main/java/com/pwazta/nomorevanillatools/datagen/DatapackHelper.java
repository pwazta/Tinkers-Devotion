package com.pwazta.nomorevanillatools.datagen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Helper class for managing datapack generation.
 * Handles folder creation, pack.mcmeta generation, recipe JSON saving, and stale recipe cleanup.
 */
public class DatapackHelper {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DATAPACK_NAME = "nomorevanillatools_generated";
    private static final String REPLACEMENT_SUFFIX = "_tinker_replacement.json";

    public static Path getDatapackPath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("datapacks").resolve(DATAPACK_NAME);
    }

    /**
     * Reloads the pack repository to discover newly written world datapacks (e.g., our generated
     * recipes pack and Mantle's {@code SlimeKnightsGenerated}), unions them into the selected
     * list (excluding any in the world's disabled list), and triggers a server resource reload.
     *
     * <p>Mirrors vanilla {@code ReloadCommand.discoverNewPacks} — necessary because
     * {@link MinecraftServer#reloadResources(Collection)} only reloads packs already in the
     * selected list and won't auto-enable packs added to {@code world/datapacks/} mid-session
     * or post-world-creation.
     */
    public static CompletableFuture<Void> discoverAndReload(MinecraftServer server) {
        PackRepository packRepository = server.getPackRepository();
        packRepository.reload();
        Collection<String> disabled = server.getWorldData().getDataConfiguration().dataPacks().getDisabled();
        List<String> selected = new ArrayList<>(packRepository.getSelectedIds());
        for (String id : packRepository.getAvailableIds()) {
            if (!disabled.contains(id) && !selected.contains(id)) {
                selected.add(id);
            }
        }
        return server.reloadResources(selected);
    }

    /**
     * Creates the pack.mcmeta file for the datapack.
     */
    public static void saveMcmeta(Path packPath) throws IOException {
        JsonObject packMcmeta = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", 15); // Minecraft 1.20.1
        pack.addProperty("description", "Generated recipes for No More Vanilla Tools");
        packMcmeta.add("pack", pack);

        Files.createDirectories(packPath);
        Files.writeString(packPath.resolve("pack.mcmeta"), GSON.toJson(packMcmeta));
    }

    /**
     * Saves a recipe JSON to the datapack.
     *
     * @return The path of the written file (for stale recipe tracking)
     */
    public static Path saveRecipeJson(JsonObject recipe, Path dataPath, String namespace, String recipeName) throws IOException {
        Path recipesDir = dataPath.resolve(namespace).resolve("recipes");
        Files.createDirectories(recipesDir);

        Path recipeFile = recipesDir.resolve(recipeName + ".json");
        Files.writeString(recipeFile, GSON.toJson(recipe));
        return recipeFile;
    }

    /**
     * Lists all generated replacement recipe files in the datapack.
     * Only finds files matching the {@code _tinker_replacement.json} naming convention.
     */
    public static Set<Path> listGeneratedRecipeFiles(MinecraftServer server) throws IOException {
        Path dataPath = getDatapackPath(server).resolve("data");
        Set<Path> recipes = new HashSet<>();
        if (!Files.exists(dataPath)) return recipes;

        try (Stream<Path> walk = Files.walk(dataPath)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(REPLACEMENT_SUFFIX))
                .forEach(recipes::add);
        }
        return recipes;
    }

    /**
     * Deletes a recipe file. Used for stale recipe cleanup.
     *
     * @return true if the file was deleted
     */
    public static boolean deleteRecipeFile(Path recipeFile) {
        try {
            return Files.deleteIfExists(recipeFile);
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isGenerated(MinecraftServer server) { return Files.exists(getDatapackPath(server).resolve(".generated")); }

    public static void markGenerated(MinecraftServer server) throws IOException {
        Path flagFile = getDatapackPath(server).resolve(".generated");
        Files.createDirectories(flagFile.getParent());
        Files.writeString(flagFile, "This file indicates that recipes have been generated. Delete to force regeneration.");
    }
}
