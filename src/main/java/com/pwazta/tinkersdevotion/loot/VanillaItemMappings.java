package com.pwazta.tinkersdevotion.loot;

import com.mojang.logging.LogUtils;
import com.pwazta.tinkersdevotion.config.VanillaTier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import slimeknights.tconstruct.library.tools.item.IModifiable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps vanilla items to their TC replacement info — which items get replaced and how.
 * Used by loot replacement (GLM, mob spawn), recipe generation, and FalseCondition overrides.
 *
 * <p>String-keyed map for recipe generation (available at any time).
 * Item-keyed map for loot replacement (lazy-initialized after registry loads).
 */
public class VanillaItemMappings {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ── Sealed interface for strategy dispatch ─────────────────────────
    public sealed interface ReplacementInfo permits ToolInfo, ArmorInfo, RangedInfo, ShieldInfo, FishingRodInfo, FlintAndSteelInfo {}

    // ── Record types ─────────────────────────────────────────────────────
    public record ToolInfo(String tier, String toolType) implements ReplacementInfo {}
    public record ArmorInfo(List<String> sets, String slot, int minTier, int maxTier) implements ReplacementInfo {}
    /** Per-part canonical material IDs in tool definition stat type order (e.g., limb, limb, grip, bowstring). Tiers derived via IMaterial.getTier(). */
    public record RangedInfo(String rangedType, List<String> canonicalMaterials) implements ReplacementInfo {}
    /** Shield info — same per-part canonical pattern as ranged. Detected via shield_core stat type, not instanceof. */
    public record ShieldInfo(String shieldType, List<String> canonicalMaterials) implements ReplacementInfo {}
    /** Fishing rod info — same per-part canonical pattern. Detected via ToolActions.FISHING_ROD_CAST (action granted by the `fishing` trait, not the definition). */
    public record FishingRodInfo(String fishingRodType, List<String> canonicalMaterials) implements ReplacementInfo {}
    /** Flint-and-steel info — material-less 1:1 swap. {@code tcItemIds} are the registry IDs of eligible TC fire-starters (only {@code tconstruct:flint_and_brick} in core TC). */
    public record FlintAndSteelInfo(String flintAndSteelType, List<String> tcItemIds) implements ReplacementInfo {}

    // ── Type/slot arrays ──────────────────────────────────────────────────

    public static final String[] TOOL_TYPES = {"sword", "pickaxe", "axe", "shovel", "hoe"};

    public static final String[] ARMOR_SLOTS = {"helmet", "chestplate", "leggings", "boots"};

    public static final String[] RANGED_TYPES = {"bow", "crossbow"};

    public static final String[] SHIELD_TYPES = {"shield"};

    public static final String[] FISHING_ROD_TYPES = {"fishing_rod"};

    public static final String[] FLINT_AND_STEEL_TYPES = {"flint_and_steel"};

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

        // Fishing rod: 3 parts (limb, bowstring, arrow_head) — wood limb + string + flint hook
        REPLACEMENTS_BY_ID.put("minecraft:fishing_rod", new FishingRodInfo("fishing_rod", List.of("tconstruct:wood", "tconstruct:string", "tconstruct:flint")));

        // Flint and steel: material-less 1:1 swap to flint_and_brick. No TC sub-tag exists for this category, so the TC item list is hardcoded.
        REPLACEMENTS_BY_ID.put("minecraft:flint_and_steel", new FlintAndSteelInfo("flint_and_steel", List.of("tconstruct:flint_and_brick")));
    }

    // ── Published maps (built-ins ∪ player aliases) ──────────────────────

    /** Immutable snapshots: readers see either the old map or the new one, never a partial state. */
    private static volatile Map<String, ReplacementInfo> byId = Map.copyOf(REPLACEMENTS_BY_ID);
    private static volatile Map<Item, ReplacementInfo> byItem;

    /**
     * Republishes the id map as built-ins plus {@code aliases}, and invalidates the item map.
     * Called on boot and whenever {@code item_aliases.json} reloads.
     */
    public static synchronized void applyAliases(Map<String, String> aliases) {
        Map<String, ReplacementInfo> merged = new HashMap<>(REPLACEMENTS_BY_ID);
        aliases.forEach((from, to) -> {
            ReplacementInfo target = REPLACEMENTS_BY_ID.get(to);
            if (target == null) {
                LOGGER.warn("Ignoring alias '{}': target '{}' is not a vanilla item we replace", from, to);
            } else if (REPLACEMENTS_BY_ID.containsKey(from)) {
                LOGGER.warn("Ignoring alias '{}': it would re-tier a vanilla item we already replace", from);
            } else if (canAlias(from)) {
                merged.put(from, target);
            }
        });
        byId = Map.copyOf(merged);
        byItem = null;
    }

    /** True if the alias key names a registered, non-Tinkers item. Warns on why it doesn't. */
    private static boolean canAlias(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            LOGGER.warn("Ignoring alias '{}': not a valid item id", id);
            return false;
        }
        // containsKey, not getValue: the item registry substitutes AIR for unknown ids.
        if (!ForgeRegistries.ITEMS.containsKey(key)) {
            // Absent namespace just means the mod is uninstalled, not worth warning about.
            if (ModList.get().isLoaded(key.getNamespace()))
                LOGGER.warn("Ignoring alias '{}': it does not resolve to a registered item", id);
            return false;
        }
        if (ForgeRegistries.ITEMS.getValue(key) instanceof IModifiable) {
            // tryReplace would match it, and EntityJoinLevelEvent re-fires on chunk load, so mob
            // gear would be rebuilt and re-enchanted every single load.
            LOGGER.warn("Ignoring alias '{}': Tinkers items cannot be replaced", id);
            return false;
        }
        return true;
    }

    private static Map<Item, ReplacementInfo> itemMap() {
        Map<Item, ReplacementInfo> map = byItem;
        if (map != null) return map;
        synchronized (VanillaItemMappings.class) {
            map = byItem;
            if (map != null) return map;
            map = new HashMap<>();
            for (Map.Entry<String, ReplacementInfo> entry : byId.entrySet()) {
                map.put(ForgeRegistries.ITEMS.getValue(new ResourceLocation(entry.getKey())), entry.getValue());
            }
            map = Map.copyOf(map);
            byItem = map;
            return map;
        }
    }

    // ── Loot lookup (Item-keyed, O(1)) ───────────────────────────────────

    /** Unified lookup for strategy dispatch. Returns null if not a mapped item. */
    public static @Nullable ReplacementInfo getReplacementInfo(Item item) {
        return itemMap().get(item);
    }

    // ── Recipe gen lookup (String-keyed, O(1)) ───────────────────────────

    /** Unified string-keyed lookup for recipe generation. */
    public static @Nullable ReplacementInfo getReplacementInfoById(String registryId) {
        return byId.get(registryId);
    }

    /** Registry IDs of every vanilla item we replace. Excludes player aliases. */
    public static Set<String> replacedItemIds() {
        return Collections.unmodifiableSet(REPLACEMENTS_BY_ID.keySet());
    }

    /** Registry IDs of the player-aliased modded items, whose own recipes also need disabling. */
    public static Set<String> aliasedItemIds() {
        Set<String> ids = new HashSet<>(byId.keySet());
        ids.removeAll(REPLACEMENTS_BY_ID.keySet());
        return ids;
    }

    // ── ArmorItem.Type resolution ────────────────────────────────────────

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
