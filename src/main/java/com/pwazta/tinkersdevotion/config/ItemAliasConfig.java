package com.pwazta.tinkersdevotion.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pwazta.tinkersdevotion.loot.VanillaItemMappings;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Player-declared aliases treating a modded item as a vanilla one, so it flows through the whole
 * replacement pipeline with no new code path.
 *
 * <p>Config file: config/tinkersdevotion/item_aliases.json
 * Format: { "modid:item_id": "minecraft:vanilla_item_id", ... }
 *
 * <p>Aliasing to a vanilla item id rather than a tier/type pair is what keeps this one syntax across
 * all six categories. Armor would otherwise need sets, slot and a tier range, and ranged/shield/rod
 * would need per-part canonical material lists.
 */
public class ItemAliasConfig {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Written on first launch and by {@code /tinkersdevotion generate reset}, never on load, so a
     * player who deletes the comment keeps it deleted. Gson parses leniently, allowing {@code //}.
     */
    private static final String TEMPLATE = """
        // Treat a modded item as the vanilla one you point it at: replaced wherever that vanilla item would be, own recipe disabled, Tinkers items excluded.
        // {
        //   "modid:example_sword": "minecraft:iron_sword",
        //   "modid:example_helmet": "minecraft:diamond_helmet"
        // }
        {}
        """;

    private static Map<String, String> aliases = Map.of();

    private static File configFile;
    private static boolean initialized = false;

    /** Sets up the config path and loads existing aliases, or creates an empty file. */
    public static void initialize(File configDir) {
        if (initialized) return;

        File modConfigDir = new File(configDir, "tinkersdevotion");
        if (!modConfigDir.exists()) modConfigDir.mkdirs();

        configFile = new File(modConfigDir, "item_aliases.json");
        if (configFile.exists()) loadConfig();
        else writeTemplate();

        initialized = true;
    }

    public static File getConfigFile() { return configFile; }

    /** Re-reads from disk and republishes the mappings. */
    public static void reload() {
        LOGGER.info("Reloading item aliases...");
        loadConfig();
    }

    /** Aliases have no defaults, so resetting restores the blank template. */
    public static void resetToDefaults() {
        LOGGER.info("Clearing item aliases...");
        aliases = Map.of();
        writeTemplate();
        VanillaItemMappings.applyAliases(aliases);
    }

    private static void loadConfig() {
        Map<String, String> loaded = new LinkedHashMap<>();
        try (FileReader reader = new FileReader(configFile)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                    JsonElement value = entry.getValue();
                    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                        loaded.put(entry.getKey(), value.getAsString());
                    } else {
                        LOGGER.warn("Ignoring alias '{}': value must be a vanilla item id string", entry.getKey());
                    }
                }
            } else if (!root.isJsonNull()) {
                // JsonNull is just an empty file, which is the shipped default.
                LOGGER.error("item_aliases.json must be a JSON object, ignoring all aliases");
            }
            LOGGER.info("Loaded {} item alias(es)", loaded.size());
        } catch (IOException e) {
            LOGGER.error("Failed to load item aliases config", e);
        } catch (Exception e) {
            LOGGER.error("Error parsing item aliases config, ignoring all aliases", e);
            loaded.clear();
        }
        aliases = Map.copyOf(loaded);
        VanillaItemMappings.applyAliases(aliases);
    }

    private static void writeTemplate() {
        try {
            Files.writeString(configFile.toPath(), TEMPLATE);
        } catch (IOException e) {
            LOGGER.error("Failed to write item aliases config", e);
        }
    }
}
