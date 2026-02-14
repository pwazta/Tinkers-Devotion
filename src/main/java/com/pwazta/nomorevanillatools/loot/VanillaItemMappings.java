package com.pwazta.nomorevanillatools.loot;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Single source of truth for vanilla tool/armor -> tier/type mappings.
 * Used by loot replacement (GLM, mob spawn), recipe generation, and FalseCondition overrides.
 *
 * String-keyed maps for recipe generation (available at any time).
 * Item-keyed maps for loot replacement (lazy-initialized after registry loads).
 */
public class VanillaItemMappings {

    // ── Record types ─────────────────────────────────────────────────────
    public record ToolInfo(String tier, String toolType) {}
    public record ArmorInfo(String tier, String slot) {}

    // ── Tier/type arrays ─────────────────────────────────────────────────

    /** All tool tiers including netherite (for loot replacement scope). */
    public static final String[] ALL_TOOL_TIERS = {"wooden", "stone", "iron", "golden", "diamond", "netherite"};

    /** Tool tiers for recipe generation — no netherite (smithing recipe, not crafting). */
    public static final String[] RECIPE_TOOL_TIERS = {"wooden", "stone", "iron", "golden", "diamond"};

    public static final String[] TOOL_TYPES = {"sword", "pickaxe", "axe", "shovel", "hoe"};

    /** All armor tiers including netherite (for loot replacement scope). */
    public static final String[] ALL_ARMOR_TIERS = {"leather", "iron", "golden", "diamond", "netherite"};

    /** Armor tiers for recipe generation — no netherite, no chainmail. */
    public static final String[] RECIPE_ARMOR_TIERS = {"leather", "iron", "golden", "diamond"};

    public static final String[] ARMOR_SLOTS = {"helmet", "chestplate", "leggings", "boots"};

    // ── String-keyed maps (populated in static init) ─────────────────────

    private static final Map<String, ToolInfo> TOOLS_BY_ID = new HashMap<>();
    private static final Map<String, ArmorInfo> ARMOR_BY_ID = new HashMap<>();

    static {
        for (String tier : ALL_TOOL_TIERS) {
            for (String tool : TOOL_TYPES) {
                TOOLS_BY_ID.put("minecraft:" + tier + "_" + tool, new ToolInfo(tier, tool));
            }
        }
        for (String tier : ALL_ARMOR_TIERS) {
            for (String slot : ARMOR_SLOTS) {
                ARMOR_BY_ID.put("minecraft:" + tier + "_" + slot, new ArmorInfo(tier, slot));
            }
        }
        // TODO: Chainmail armor replacement (P3)
        // Chainmail has no crafting recipe but appears in mob drops and some loot tables.
        // Currently excluded — stays as vanilla. Needs tier mapping decision (iron-tier?).
    }

    // ── Item-keyed maps (lazy-initialized on first access) ───────────────

    private static volatile Map<Item, ToolInfo> toolsByItem;
    private static volatile Map<Item, ArmorInfo> armorByItem;

    private static void ensureItemMaps() {
        if (toolsByItem != null) return;
        synchronized (VanillaItemMappings.class) {
            if (toolsByItem != null) return;
            Map<Item, ToolInfo> tools = new HashMap<>();
            Map<Item, ArmorInfo> armor = new HashMap<>();
            for (Map.Entry<String, ToolInfo> entry : TOOLS_BY_ID.entrySet()) {
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(entry.getKey()));
                if (item != null) tools.put(item, entry.getValue());
            }
            for (Map.Entry<String, ArmorInfo> entry : ARMOR_BY_ID.entrySet()) {
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(entry.getKey()));
                if (item != null) armor.put(item, entry.getValue());
            }
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

    // ── Recipe gen lookup (String-keyed) ──────────────────────────────────

    /** Look up tool info by registry ID string. Returns null if not a vanilla tool. */
    public static @Nullable ToolInfo getToolInfoById(String registryId) {
        return TOOLS_BY_ID.get(registryId);
    }

    /** Look up armor info by registry ID string. Returns null if not a vanilla armor piece. */
    public static @Nullable ArmorInfo getArmorInfoById(String registryId) {
        return ARMOR_BY_ID.get(registryId);
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
