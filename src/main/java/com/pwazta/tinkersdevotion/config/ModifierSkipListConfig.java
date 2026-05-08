package com.pwazta.tinkersdevotion.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pwazta.tinkersdevotion.loot.EnchantmentConverter;
import org.slf4j.Logger;
import slimeknights.tconstruct.library.modifiers.ModifierId;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Player-editable modifier exclusion list for enchantment-to-modifier conversion.
 * Modifiers in this list are never randomly applied to loot/mob equipment.
 *
 * Config file: config/tinkersdevotion/modifier_skip_list.json
 * Format: ["modid:modifier_id", ...]
 *
 * Follows the same lifecycle pattern as {@link ToolExclusionConfig}:
 * initialize (commonSetup) → reload (generate command) → resetToDefaults (generate reset).
 */
public class ModifierSkipListConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final List<String> DEFAULT_SKIP_LIST = List.of(
        "tconstruct:netherite",
        "tconstruct:creative_slot",
        "tconstruct:draconic",
        "tconstruct:dragonborn",
        "tconstruct:dragonshot",
        "tconstruct:rebalanced",
        "tconstruct:gilded",
        "tconstruct:unbreakable",
        "tconstruct:shiny",
        "tconstruct:worldbound",
        "tconstruct:wings",
        "tconstruct:pockets",
        "tconstruct:shulking",
        "tconstruct:flamewake",
        // Addon modifiers (ignored if mod absent — ModifierId won't resolve)
        "tinkerslevellingaddon:improvable",
        "tcintegrations:flamed",
        "tcintegrations:iced",
        "tcintegrations:zapped"
    );

    private static volatile Set<ModifierId> skipList = Set.of();
    private static File configFile;
    private static boolean initialized = false;

    /**
     * Sets up config path and loads existing config, or creates defaults.
     * Called from commonSetup — no registry dependency.
     */
    public static void initialize(File configDir) {
        if (initialized) return;

        File modConfigDir = new File(configDir, "tinkersdevotion");
        if (!modConfigDir.exists()) modConfigDir.mkdirs();

        configFile = new File(modConfigDir, "modifier_skip_list.json");

        if (configFile.exists()) {
            LOGGER.info("Loading modifier skip list from: {}", configFile.getAbsolutePath());
            loadConfig();
        } else {
            LOGGER.info("No modifier skip list config found — creating defaults");
            writeDefaults();
            loadConfig();
        }

        initialized = true;
    }

    /** Returns the current set of modifier IDs excluded from the random pool. */
    public static Set<ModifierId> getSkipList() {
        return skipList;
    }

    /** Reloads the skip list from disk and invalidates the modifier pool cache. */
    public static void reload() {
        LOGGER.info("Reloading modifier skip list...");
        loadConfig();
        EnchantmentConverter.clearCache();
    }

    /** Resets the skip list to defaults and invalidates the modifier pool cache. */
    public static void resetToDefaults() {
        LOGGER.info("Resetting modifier skip list to defaults...");
        writeDefaults();
        loadConfig();
        EnchantmentConverter.clearCache();
    }

    private static void loadConfig() {
        Set<ModifierId> loaded = new HashSet<>();
        try (FileReader reader = new FileReader(configFile)) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement element : array) {
                ModifierId id = ModifierId.tryParse(element.getAsString());
                if (id != null) {
                    loaded.add(id);
                } else {
                    LOGGER.warn("Invalid modifier ID in skip list: {}", element.getAsString());
                }
            }
            skipList = Collections.unmodifiableSet(loaded);
            LOGGER.info("Loaded {} modifier skip list entries", skipList.size());
        } catch (IOException e) {
            LOGGER.error("Failed to load modifier skip list config", e);
        } catch (Exception e) {
            LOGGER.error("Error parsing modifier skip list config", e);
        }
    }

    private static void writeDefaults() {
        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(DEFAULT_SKIP_LIST, writer);
            LOGGER.debug("Wrote default modifier skip list to: {}", configFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Failed to write modifier skip list defaults", e);
        }
    }

    public static File getConfigFile() { return configFile; }
}
