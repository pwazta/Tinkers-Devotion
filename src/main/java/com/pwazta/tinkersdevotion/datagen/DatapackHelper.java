package com.pwazta.tinkersdevotion.datagen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.pwazta.tinkersdevotion.config.ToolExclusionConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Folder creation, pack.mcmeta + recipe JSON writing, stale cleanup, and the hash marker
 * that drives auto-regeneration on mod-set or tool_exclusions.json changes.
 */
public class DatapackHelper {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DATAPACK_NAME = "tinkersdevotion_generated";
    private static final String REPLACEMENT_SUFFIX = "_tinker_replacement.json";
    private static final String HASH_FILE = ".generated.hash";
    private static final String LEGACY_FLAG = ".generated";

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
        pack.addProperty("description", "Generated recipes for Tinkers' Devotion");
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

    /** Also deletes the pre-1.0.1 .generated flag if present, so upgraded worlds are clean. */
    public static void markGenerated(MinecraftServer server) throws IOException {
        Path packPath = getDatapackPath(server);
        Files.createDirectories(packPath);
        Files.writeString(packPath.resolve(HASH_FILE), computeHash(server));
        Files.deleteIfExists(packPath.resolve(LEGACY_FLAG));
    }

    public static @Nullable String readStoredHash(MinecraftServer server) {
        Path hashFile = getDatapackPath(server).resolve(HASH_FILE);
        if (!Files.exists(hashFile)) return null;
        try {
            return Files.readString(hashFile).trim();
        } catch (IOException e) {
            LOGGER.warn("Failed to read generated-hash file at {}, will treat as stale", hashFile, e);
            return null;
        }
    }

    /**
     * Hashes every loaded mod (modid:version, sorted) plus the raw bytes of tool_exclusions.json.
     * Other configs don't feed recipe JSON output, so they're excluded to avoid false-positive regens
     * on unrelated runtime-only edits.
     */
    public static String computeHash(MinecraftServer server) {
        List<String> parts = new ArrayList<>();
        for (IModInfo mod : ModList.get().getMods()) {
            parts.add(mod.getModId() + ":" + mod.getVersion().toString());
        }
        Collections.sort(parts);
        parts.add("--exclusions--");
        parts.add(readExclusionConfigBytes());

        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String readExclusionConfigBytes() {
        File configFile = ToolExclusionConfig.getConfigFile();
        if (configFile == null || !configFile.exists()) return "";
        try {
            return new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to read {} for hash, treating as empty", configFile, e);
            return "";
        }
    }
}
