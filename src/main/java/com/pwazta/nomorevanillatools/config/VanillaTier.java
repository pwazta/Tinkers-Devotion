package com.pwazta.nomorevanillatools.config;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Defines what a vanilla tier IS — item prefix, TC tier path, and canonical tool material.
 * Used by {@link TiersToTcMaterials} for tier↔material resolution and by
 * {@link com.pwazta.nomorevanillatools.loot.VanillaItemMappings} for vanilla item registration.
 */
public enum VanillaTier {
    WOOD     ("wooden",    "wood",      "tconstruct:wood"),
    STONE    ("stone",     "stone",     "tconstruct:rock"),
    IRON     ("iron",      "iron",      "tconstruct:iron"),
    GOLD     ("golden",    "gold",      "tconstruct:rose_gold"),
    DIAMOND  ("diamond",   "diamond",   "tconstruct:cobalt"),
    NETHERITE("netherite", "netherite",  "tconstruct:hepatizon");

    private final String itemPrefix;
    private final String tcTierPath;
    private final String canonicalToolMaterial;

    VanillaTier(String itemPrefix, String tcTierPath, String canonicalToolMaterial) {
        this.itemPrefix = itemPrefix;
        this.tcTierPath = tcTierPath;
        this.canonicalToolMaterial = canonicalToolMaterial;
    }

    /** Vanilla item ID prefix (e.g. "golden" for golden_pickaxe, "iron" for iron_chestplate). */
    public String itemPrefix() { return itemPrefix; }

    /** TC tier path component — matches {@code TierSortingRegistry.getName(tier).getPath()} (e.g. "gold" from minecraft:gold). */
    public String tcTierPath() { return tcTierPath; }

    /** Canonical TC tool material ID for loot weighting (80% selection) and JEI display (e.g. "tconstruct:rose_gold"). */
    public String canonicalToolMaterial() { return canonicalToolMaterial; }

    /**
     * Lookup by vanilla Tier ResourceLocation (e.g. minecraft:gold → GOLD).
     * Used during {@link TiersToTcMaterials#rebuildToolCaches()} to resolve HeadMaterialStats.tier().
     * Returns null for non-vanilla or modded tiers — callers handle null as "unmapped material".
     */
    public static @Nullable VanillaTier fromResourceLocation(ResourceLocation tierRL) {
        if (!"minecraft".equals(tierRL.getNamespace())) return null;
        String path = tierRL.getPath();
        for (VanillaTier tier : values())
            if (tier.tcTierPath.equals(path)) return tier;
        return null;
    }

    /**
     * Lookup by item prefix (e.g. "iron" → IRON, "golden" → GOLD).
     * Used to resolve tier name strings from caches back to enum values.
     * Returns null if not found.
     */
    public static @Nullable VanillaTier fromItemPrefix(String prefix) {
        for (VanillaTier tier : values())
            if (tier.itemPrefix.equals(prefix)) return tier;
        return null;
    }
}
