package com.pwazta.nomorevanillatools.loot;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for vanilla tool/armor/ranged -> tier/type mappings.
 * Used by loot replacement (GLM, mob spawn), recipe generation, and FalseCondition overrides.
 *
 * String-keyed maps for recipe generation (available at any time).
 * Item-keyed maps for loot replacement (lazy-initialized after registry loads).
 */
public class VanillaItemMappings {

    // ── Sealed interface for strategy dispatch ─────────────────────────
    public sealed interface ReplacementInfo permits ToolInfo, ArmorInfo, RangedInfo {}

    // ── Record types ─────────────────────────────────────────────────────
    public record ToolInfo(String tier, String toolType) implements ReplacementInfo {}
    public record ArmorInfo(String set, String slot, int minTier, int maxTier) implements ReplacementInfo {}
    /** Per-part tier list matches the tool definition's stat type order (e.g., limb, limb, grip, bowstring). */
    public record RangedInfo(String rangedType, List<String> partTiers) implements ReplacementInfo {}

    // ── Tier/type arrays ─────────────────────────────────────────────────

    /** All tool tiers including netherite (for loot replacement scope). */
    public static final String[] ALL_TOOL_TIERS = {"wooden", "stone", "iron", "golden", "diamond", "netherite"};

    /** Tool tiers for recipe generation — no netherite (smithing recipe, not crafting). */
    public static final String[] RECIPE_TOOL_TIERS = {"wooden", "stone", "iron", "golden", "diamond"};

    public static final String[] TOOL_TYPES = {"sword", "pickaxe", "axe", "shovel", "hoe"};

    /** Armor tiers for recipe generation — no netherite (smithing), no chainmail (no recipe). */
    public static final String[] RECIPE_ARMOR_TIERS = {"leather", "iron", "golden", "diamond"};

    public static final String[] ARMOR_SLOTS = {"helmet", "chestplate", "leggings", "boots"};

    public static final String[] RANGED_TYPES = {"bow", "crossbow"};

    // ── String-keyed maps (populated in static init) ─────────────────────

    private static final Map<String, ToolInfo> TOOLS_BY_ID = new HashMap<>();
    private static final Map<String, ArmorInfo> ARMOR_BY_ID = new HashMap<>();
    private static final Map<String, RangedInfo> RANGED_BY_ID = new HashMap<>();

    static {
        for (String tier : ALL_TOOL_TIERS) {
            for (String tool : TOOL_TYPES) {
                TOOLS_BY_ID.put("minecraft:" + tier + "_" + tool, new ToolInfo(tier, tool));
            }
        }
        // Armor: mapped by set (travelers/plate) + IMaterial.getTier() range
        // TODO (P2): TC plating defense at tier N < vanilla tier N because TC assumes diamond modifier.
        // Replaced armor may be weaker than vanilla. See PLANNING-armor-rework.md for options.
        for (String slot : ARMOR_SLOTS) {
            ARMOR_BY_ID.put("minecraft:leather_" + slot,    new ArmorInfo("travelers", slot, 0, 1));
            ARMOR_BY_ID.put("minecraft:chainmail_" + slot,  new ArmorInfo("travelers", slot, 2, 2));
            ARMOR_BY_ID.put("minecraft:golden_" + slot,     new ArmorInfo("plate", slot, 0, 1));
            ARMOR_BY_ID.put("minecraft:iron_" + slot,       new ArmorInfo("plate", slot, 2, 2));
            ARMOR_BY_ID.put("minecraft:diamond_" + slot,    new ArmorInfo("plate", slot, 3, 3));
            ARMOR_BY_ID.put("minecraft:netherite_" + slot,  new ArmorInfo("plate", slot, 3, 3));
        }

        // Ranged weapons — per-part tiers match TC tool definition stat type order
        // Bow: 4 parts (limb, limb, grip, bowstring) — all wooden tier
        RANGED_BY_ID.put("minecraft:bow", new RangedInfo("bow", List.of("wooden", "wooden", "wooden", "wooden")));
        // Crossbow: 3 parts (limb, grip, bowstring) — iron grip matches vanilla iron ingot ingredient
        RANGED_BY_ID.put("minecraft:crossbow", new RangedInfo("crossbow", List.of("wooden", "iron", "wooden")));
    }

    // ── Item-keyed maps (lazy-initialized on first access) ───────────────

    private static volatile Map<Item, ToolInfo> toolsByItem;
    private static volatile Map<Item, ArmorInfo> armorByItem;
    private static volatile Map<Item, RangedInfo> rangedByItem;

    private static void ensureItemMaps() {
        if (toolsByItem != null) return;
        synchronized (VanillaItemMappings.class) {
            if (toolsByItem != null) return;
            Map<Item, ToolInfo> tools = new HashMap<>();
            Map<Item, ArmorInfo> armor = new HashMap<>();
            Map<Item, RangedInfo> ranged = new HashMap<>();
            for (Map.Entry<String, ToolInfo> entry : TOOLS_BY_ID.entrySet()) {
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(entry.getKey()));
                if (item != null) tools.put(item, entry.getValue());
            }
            for (Map.Entry<String, ArmorInfo> entry : ARMOR_BY_ID.entrySet()) {
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(entry.getKey()));
                if (item != null) armor.put(item, entry.getValue());
            }
            for (Map.Entry<String, RangedInfo> entry : RANGED_BY_ID.entrySet()) {
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(entry.getKey()));
                if (item != null) ranged.put(item, entry.getValue());
            }
            rangedByItem = ranged;
            armorByItem = armor;
            toolsByItem = tools;
        }
    }

    // ── Loot lookup (Item-keyed, O(1)) ───────────────────────────────────

    /** Look up tool info by Item reference. Returns null if not a vanilla tool. */
    public static @Nullable ToolInfo getToolInfo(Item item) {
        ensureItemMaps();
        return toolsByItem.get(item);
    }

    /** Look up armor info by Item reference. Returns null if not a vanilla armor piece. */
    public static @Nullable ArmorInfo getArmorInfo(Item item) {
        ensureItemMaps();
        return armorByItem.get(item);
    }

    /** Look up ranged info by Item reference. Returns null if not a vanilla ranged weapon. */
    public static @Nullable RangedInfo getRangedInfo(Item item) {
        ensureItemMaps();
        return rangedByItem.get(item);
    }

    /** Unified lookup for strategy dispatch. Returns null if not a mapped vanilla item. */
    public static @Nullable ReplacementInfo getReplacementInfo(Item item) {
        ensureItemMaps();
        ToolInfo tool = toolsByItem.get(item);
        if (tool != null) return tool;
        ArmorInfo armor = armorByItem.get(item);
        if (armor != null) return armor;
        return rangedByItem.get(item);
    }

    // ── Recipe gen lookup (String-keyed) ──────────────────────────────────

    /** Look up tool info by registry ID string. Returns null if not a vanilla tool. */
    public static @Nullable ToolInfo getToolInfoById(String registryId) {
        return TOOLS_BY_ID.get(registryId);
    }

    /** Look up armor info by registry ID string. Returns null if not a vanilla armor piece. */
    public static @Nullable ArmorInfo getArmorInfoById(String registryId) {
        return ARMOR_BY_ID.get(registryId);
    }

    /** Look up ranged info by registry ID string. Returns null if not a vanilla ranged weapon. */
    public static @Nullable RangedInfo getRangedInfoById(String registryId) {
        return RANGED_BY_ID.get(registryId);
    }

    // ── ToolAction / ArmorItem.Type resolution ───────────────────────────

    /** Maps tool type name to the ToolAction used for TC tool scanning. */
    public static @Nullable ToolAction getToolAction(String toolType) {
        return switch (toolType.toLowerCase()) {
            case "sword"   -> ToolActions.SWORD_DIG;
            case "pickaxe" -> ToolActions.PICKAXE_DIG;
            case "axe"     -> ToolActions.AXE_DIG;
            case "shovel"  -> ToolActions.SHOVEL_DIG;
            case "hoe"     -> ToolActions.HOE_DIG;
            default        -> null;
        };
    }

    /** Maps armor slot name to ArmorItem.Type. */
    public static @Nullable ArmorItem.Type getArmorType(String slot) {
        return switch (slot.toLowerCase()) {
            case "helmet"     -> ArmorItem.Type.HELMET;
            case "chestplate" -> ArmorItem.Type.CHESTPLATE;
            case "leggings"   -> ArmorItem.Type.LEGGINGS;
            case "boots"      -> ArmorItem.Type.BOOTS;
            default           -> null;
        };
    }
}
