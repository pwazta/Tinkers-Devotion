package com.pwazta.tinkersdevotion.compat.jei;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;

/**
 * Loads extra crafting container configurations from TOML file.
 * Allows users to add support for modded containers without code changes.
 */
public class CraftingContainerConfigLoader {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CONFIG_FILE_NAME = "tinkersdevotion-containers.toml";

    /**
     * Loads extra container configurations from the config file.
     * Creates the file with template if it doesn't exist.
     * Validates each entry and registers valid configs into CraftingContainerRegistry.
     *
     * @param configDir The config directory path (from FMLPaths.CONFIGDIR)
     */
    public static void loadExtrasFromConfig(Path configDir) {
        Path configPath = configDir.resolve(CONFIG_FILE_NAME);

        try {
            // Create config with default template if not exists
            CommentedFileConfig config = CommentedFileConfig.builder(configPath.toFile())
                    .defaultResource("default_containers.toml")
                    .preserveInsertionOrder()
                    .build();

            config.load();

            // Parse [[extra_containers]] array
            List<? extends Config> containers = config.get("extra_containers");

            if (containers == null || containers.isEmpty()) {
                LOGGER.debug("No extra containers configured in {}", CONFIG_FILE_NAME);
                config.close();
                return;
            }

            int loaded = 0;
            int skipped = 0;

            for (Config containerConfig : containers) {
                try {
                    CraftingContainerConfig parsed = parseContainerConfig(containerConfig);
                    if (parsed != null) {
                        // Validate class exists
                        if (validateContainerClass(parsed.containerClassName())) {
                            CraftingContainerRegistry.register(parsed);
                            LOGGER.info("Loaded extra container config: {} -> {}",
                                    parsed.id(), parsed.containerClassName());
                            loaded++;
                        } else {
                            LOGGER.warn("Skipping container '{}': class not found: {}",
                                    parsed.id(), parsed.containerClassName());
                            skipped++;
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to parse container config entry: {}", e.getMessage());
                    skipped++;
                }
            }

            LOGGER.info("Loaded {} extra container configs ({} skipped) from {}",
                    loaded, skipped, CONFIG_FILE_NAME);

            config.close();

        } catch (Exception e) {
            LOGGER.debug("No extra container configs loaded from {}: {}",
                    CONFIG_FILE_NAME, e.getMessage());
        }
    }

    /**
     * Parses a single container configuration from the config object.
     *
     * @param containerConfig The NightConfig Config object for one [[extra_containers]] entry
     * @return Parsed CraftingContainerConfig, or null if invalid
     */
    private static CraftingContainerConfig parseContainerConfig(Config containerConfig) {
        // Required fields
        String id = containerConfig.get("id");
        String className = containerConfig.get("class");
        Integer gridWidth = containerConfig.get("grid_width");
        Integer gridHeight = containerConfig.get("grid_height");
        Integer craftingSlotStart = containerConfig.get("crafting_slot_start");
        Integer craftingSlotEnd = containerConfig.get("crafting_slot_end");
        Integer inventorySlotStart = containerConfig.get("inventory_slot_start");
        Integer inventorySlotEnd = containerConfig.get("inventory_slot_end");

        // Validate required fields
        if (id == null || id.isBlank()) {
            LOGGER.warn("Container config missing 'id' field");
            return null;
        }
        if (className == null || className.isBlank()) {
            LOGGER.warn("Container config '{}' missing 'class' field", id);
            return null;
        }
        if (gridWidth == null || gridHeight == null) {
            LOGGER.warn("Container config '{}' missing grid dimensions", id);
            return null;
        }
        if (craftingSlotStart == null || craftingSlotEnd == null) {
            LOGGER.warn("Container config '{}' missing crafting slot range", id);
            return null;
        }
        if (inventorySlotStart == null || inventorySlotEnd == null) {
            LOGGER.warn("Container config '{}' missing inventory slot range", id);
            return null;
        }

        // Check for duplicate ID
        if (CraftingContainerRegistry.hasConfig(id)) {
            LOGGER.warn("Container config '{}' has duplicate ID, will overwrite", id);
        }

        return new CraftingContainerConfig(
                id,
                className,
                gridWidth,
                gridHeight,
                craftingSlotStart,
                craftingSlotEnd,
                inventorySlotStart,
                inventorySlotEnd
        );
    }

    /**
     * Validates that the container class exists and can be loaded.
     *
     * @param className Full class name to validate
     * @return true if class exists, false otherwise
     */
    private static boolean validateContainerClass(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
