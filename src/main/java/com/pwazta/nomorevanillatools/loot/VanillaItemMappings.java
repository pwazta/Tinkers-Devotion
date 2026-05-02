package com.pwazta.nomorevanillatools.loot;

import com.pwazta.nomorevanillatools.config.VanillaTier;
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
 * Maps vanilla items to their TC replacement info — which items get replaced and how.
 * Used by loot replacement (GLM, mob spawn), recipe generation, and FalseCondition overrides.
 *
 * <p>String-keyed map for recipe generation (available at any time).
 * Item-keyed map for loot replacement (lazy-initialized after registry loads).
 */
public class VanillaItemMappings {

    // ── Sealed interface for strategy dispatch ─────────────────────────
    public sealed interface ReplacementInfo permits ToolInfo, ArmorInfo, RangedInfo, ShieldInfo {}

    // ── Record types ─────────────────────────────────────────────────────
    public record ToolInfo(String tier, String toolType) implements ReplacementInfo {}
    public record ArmorInfo(List<String> sets, String slot, int minTier, int maxTier) implements ReplacementInfo {}
    /** Per-part canonical material IDs in tool definition stat type order (e.g., limb, limb, grip, bowstring). Tiers derived via IMaterial.getTier(). */
    public record RangedInfo(String rangedType, List<String> canonicalMaterials) implements ReplacementInfo {}
    /** Shield info — same per-part canonical pattern as ranged. Detected via shield_core stat type, not instanceof. */
    public record ShieldInfo(String shieldType, List<String> canonicalMaterials) implements ReplacementInfo {}

    // ── Type/slot arrays ──────────────────────────────────────────────────

    public static final String[] TOOL_TYPES = {"sword", "pickaxe", "axe", "shovel", "hoe"};

    public static final String[] ARMOR_SLOTS = {"helmet", "chestplate", "leggings", "boots"};

    public static final String[] RANGED_TYPES = {"bow", "crossbow"};

    public static final String[] SHIELD_TYPES = {"shield"};

    // ── String-keyed map (populated in static init) ────────────────────

    private static final Map<String, ReplacementInfo> REPLACEMENTS_BY_ID = new HashMap<>();

    static {
        for (VanillaTier tier : VanillaTier.values()) {
            for (String tool : TOOL_TYPES) {
                REPLACEMENTS_BY_ID.put("minecraft:" + tier.itemPrefix() + "_" + tool, new ToolInfo(tier.itemPrefix(), tool));
            }
        }
        // Armor: mapped by allowed TC sets (full namespace) + IMaterial.getTier() range.
        // Multiple sets per tier allow addon armor (TinkersThings laminar/makeshift) to appear in loot.
        // TODO (P2): TC plating defense at tier N < vanilla tier N because TC assumes diamond modifier.
        // Replaced armor may be weaker than vanilla. See PLANNING-armor-rework.md for options.
        for (String slot : ARMOR_SLOTS) {
            REPLACEMENTS_BY_ID.put("minecraft:leather_" + slot,    new ArmorInfo(List.of("tconstruct:travelers", "tinkers_things:makeshift"), slot, 0, 1));
            REPLACEMENTS_BY_ID.put("minecraft:chainmail_" + slot,  new ArmorInfo(List.of("tconstruct:travelers", "tinkers_things:laminar"), slot, 2, 2));
            REPLACEMENTS_BY_ID.put("minecraft:golden_" + slot,     new ArmorInfo(List.of("tconstruct:travelers", "tconstruct:plate", "tinkers_things:laminar"), slot, 1, 2));
            REPLACEMENTS_BY_ID.put("minecraft:iron_" + slot,       new ArmorInfo(List.of("tconstruct:plate", "tinkers_things:laminar"), slot, 2, 2));
            REPLACEMENTS_BY_ID.put("minecraft:diamond_" + slot,    new ArmorInfo(List.of("tconstruct:plate", "tinkers_things:laminar"), slot, 3, 3));
            REPLACEMENTS_BY_ID.put("minecraft:netherite_" + slot,  new ArmorInfo(List.of("tconstruct:plate", "tinkers_things:laminar"), slot, 4, 4));
        }

        // Ranged weapons — canonical materials per part in TC tool definition stat type order
        // Bow: 4 parts (limb, limb, grip, bowstring)
        REPLACEMENTS_BY_ID.put("minecraft:bow", new RangedInfo("bow", List.of("tconstruct:wood", "tconstruct:wood", "tconstruct:wood", "tconstruct:string")));
        // Crossbow: 3 parts (limb, grip, bowstring) — iron grip matches vanilla iron ingot ingredient
        REPLACEMENTS_BY_ID.put("minecraft:crossbow", new RangedInfo("crossbow", List.of("tconstruct:wood", "tconstruct:iron", "tconstruct:string")));

        // Shield: 2 parts (shield_core, cuirass/plating_shield) — wood planks + iron ingot
        REPLACEMENTS_BY_ID.put("minecraft:shield", new ShieldInfo("shield", List.of("tconstruct:wood", "tconstruct:iron")));
    }

    // ── Item-keyed map (lazy-initialized on first access) ────────────────

    private static volatile Map<Item, ReplacementInfo> replacementsByItem;

    private static void ensureItemMap() {
        if (replacementsByItem != null) return;
        synchronized (VanillaItemMappings.class) {
            if (replacementsByItem != null) return;
            Map<Item, ReplacementInfo> map = new HashMap<>();
            for (Map.Entry<String, ReplacementInfo> entry : REPLACEMENTS_BY_ID.entrySet()) {
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(entry.getKey()));
                if (item != null) map.put(item, entry.getValue());
            }
            replacementsByItem = map;
        }
    }

    // ── Loot lookup (Item-keyed, O(1)) ───────────────────────────────────

    /** Unified lookup for strategy dispatch. Returns null if not a mapped vanilla item. */
    public static @Nullable ReplacementInfo getReplacementInfo(Item item) {
        ensureItemMap();
        return replacementsByItem.get(item);
    }

    // ── Recipe gen lookup (String-keyed, O(1)) ───────────────────────────

    /** Unified string-keyed lookup for recipe generation. */
    public static @Nullable ReplacementInfo getReplacementInfoById(String registryId) {
        return REPLACEMENTS_BY_ID.get(registryId);
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
