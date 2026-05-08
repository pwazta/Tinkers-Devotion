package com.pwazta.tinkersdevotion.compat.jei;

import com.mojang.logging.LogUtils;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.*;

/**
 * Registry for crafting container configurations.
 * Provides lookup by ID and container class matching.
 * Designed for future config-file extensibility.
 */
public class CraftingContainerRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<String, CraftingContainerConfig> BY_ID = new HashMap<>();
    private static final List<CraftingContainerConfig> ALL = new ArrayList<>();
    private static boolean initialized = false;

    /**
     * Registers a crafting container configuration.
     * Duplicate IDs will overwrite existing entries.
     *
     * @param config The configuration to register
     */
    public static void register(CraftingContainerConfig config) {
        if (BY_ID.containsKey(config.id())) {
            LOGGER.warn("Overwriting existing crafting container config: {}", config.id());
            ALL.removeIf(c -> c.id().equals(config.id()));
        }
        BY_ID.put(config.id(), config);
        ALL.add(config);
        LOGGER.debug("Registered crafting container config: {} -> {}", config.id(), config.containerClassName());
    }

    /** Gets a configuration by its ID, or null if not found. */
    public static CraftingContainerConfig getById(String id) { return BY_ID.get(id); }

    /**
     * Finds the configuration that matches the given container.
     * Iterates through all registered configs and returns the first match.
     *
     * @param container The container to match
     * @return Optional containing the matching config, or empty if none match
     */
    public static Optional<CraftingContainerConfig> findForContainer(AbstractContainerMenu container) {
        if (container == null) return Optional.empty();

        for (CraftingContainerConfig config : ALL) {
            if (config.matchesContainer(container)) {
                return Optional.of(config);
            }
        }
        return Optional.empty();
    }

    public static List<CraftingContainerConfig> getAll() { return Collections.unmodifiableList(ALL); }
    public static boolean hasConfig(String id) { return BY_ID.containsKey(id); }

    /**
     * Registers all built-in container configurations.
     * Should be called during mod initialization (commonSetup).
     */
    public static void registerBuiltIns() {
        if (initialized) {
            LOGGER.warn("CraftingContainerRegistry.registerBuiltIns() called multiple times");
            return;
        }

        register(CraftingContainerConfig.CRAFTING_TABLE);
        register(CraftingContainerConfig.PLAYER_INVENTORY);
        if (ModList.get().isLoaded("tconstruct")) {
            register(CraftingContainerConfig.TC_CRAFTING_STATION);
        }

        initialized = true;
        LOGGER.info("Registered {} built-in crafting container configs", ALL.size());
    }

    public static boolean isInitialized() { return initialized; }
}
