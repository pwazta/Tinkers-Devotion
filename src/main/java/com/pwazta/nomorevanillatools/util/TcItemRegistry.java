package com.pwazta.nomorevanillatools.util;

import com.pwazta.nomorevanillatools.config.ToolExclusionConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.Tags;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.registries.ForgeRegistries;
import slimeknights.tconstruct.library.materials.stats.MaterialStatsId;
import slimeknights.tconstruct.library.tools.definition.ToolDefinition;
import slimeknights.tconstruct.library.tools.definition.module.ToolHooks;
import slimeknights.tconstruct.library.tools.definition.module.material.ToolMaterialHook;
import slimeknights.tconstruct.library.tools.item.IModifiable;
import slimeknights.tconstruct.library.tools.item.IModifiableDisplay;
import slimeknights.tconstruct.library.tools.item.armor.ModifiableArmorItem;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableBowItem;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableCrossbowItem;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached registry scanner for eligible TC tools, armor, and ranged weapons.
 *
 * <p>Shared by {@link com.pwazta.nomorevanillatools.loot.TinkerToolBuilder TinkerToolBuilder}
 * (loot replacement) and {@link com.pwazta.nomorevanillatools.recipe.TinkerMaterialIngredient
 * TinkerMaterialIngredient} (recipe matching / JEI display) to avoid duplicating the same
 * registry scan and exclusion filtering logic.
 *
 * <p>Caches are cleared on config reload via {@link #clearCaches()}.
 */
public final class TcItemRegistry {

    private TcItemRegistry() {}

    /** Eligible TC tools per action name. */
    private static final Map<String, List<IModifiable>> TOOL_CACHE = new ConcurrentHashMap<>();

    /** Eligible TC armor per slot name. */
    private static final Map<String, List<IModifiable>> ARMOR_CACHE = new ConcurrentHashMap<>();

    /** Eligible TC ranged weapons per type name. */
    private static final Map<String, List<IModifiable>> RANGED_CACHE = new ConcurrentHashMap<>();

    /** Eligible TC shields per type name. */
    private static final Map<String, List<IModifiable>> SHIELD_CACHE = new ConcurrentHashMap<>();

    /** Eligible TC fishing rods per type name. */
    private static final Map<String, List<IModifiable>> FISHING_ROD_CACHE = new ConcurrentHashMap<>();

    /** Clears all cached scan results. Called on config/material reload. */
    public static void clearCaches() {
        TOOL_CACHE.clear();
        ARMOR_CACHE.clear();
        RANGED_CACHE.clear();
        SHIELD_CACHE.clear();
        FISHING_ROD_CACHE.clear();
    }

    /**
     * Returns all TC tools that support the given {@link ToolAction}, excluding items in
     * {@link ToolExclusionConfig}. Results are cached per action name.
     */
    public static List<IModifiable> getEligibleTools(ToolAction action, String actionName) {
        return TOOL_CACHE.computeIfAbsent(actionName, k -> {
            List<IModifiable> tools = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS) {
                if (!(item instanceof IModifiableDisplay display)) continue;
                if (!(item instanceof IModifiable modifiable)) continue;
                ToolDefinition definition = display.getToolDefinition();
                if (!definition.isDataLoaded()) continue;

                ItemStack renderStack = display.getRenderTool();
                boolean supportsAction = definition.getData().getHook(ToolHooks.TOOL_ACTION)
                    .canPerformAction(ToolStack.from(renderStack), action);
                if (!supportsAction) continue;

                ResourceLocation toolId = ForgeRegistries.ITEMS.getKey(item);
                if (toolId != null && ToolExclusionConfig.isExcluded(actionName, toolId.toString())) continue;

                tools.add(modifiable);
            }
            return tools;
        });
    }

    /**
     * Returns all TC armor pieces matching the given {@link ArmorItem.Type} and any of the allowed
     * armor set prefixes, excluding items in {@link ToolExclusionConfig}. Results are cached.
     *
     * @param armorType the ArmorItem.Type to match (HELMET, CHESTPLATE, etc.)
     * @param sets      allowed armor set prefixes with full namespace (e.g., "tconstruct:plate", "tinkers_things:laminar")
     * @param slotName  the slot name for exclusion checking and cache key
     */
    public static List<IModifiable> getEligibleArmor(ArmorItem.Type armorType, List<String> sets, String slotName) {
        String cacheKey = sets + ":" + slotName;
        return ARMOR_CACHE.computeIfAbsent(cacheKey, k -> {
            List<IModifiable> armor = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS) {
                if (!(item instanceof ModifiableArmorItem armorItem)) continue;
                if (armorItem.getType() != armorType) continue;

                ResourceLocation armorId = ForgeRegistries.ITEMS.getKey(item);
                if (armorId == null) continue;
                String idStr = armorId.toString();
                if (ToolExclusionConfig.isExcluded(slotName, idStr)) continue;

                boolean matchesSet = false;
                for (String set : sets) {
                    if (idStr.startsWith(set + "_")) { matchesSet = true; break; }
                }
                if (!matchesSet) continue;

                armor.add(armorItem);
            }
            return armor;
        });
    }

    /**
     * Returns all TC armor pieces matching the given {@link ArmorItem.Type} regardless of set,
     * excluding items in {@link ToolExclusionConfig}. Used by recipe JEI display (any set at matching tier).
     */
    public static List<IModifiable> getAllEligibleArmor(ArmorItem.Type armorType, String slotName) {
        String cacheKey = "all:" + slotName;
        return ARMOR_CACHE.computeIfAbsent(cacheKey, k -> {
            List<IModifiable> armor = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS) {
                if (!(item instanceof ModifiableArmorItem armorItem)) continue;
                if (armorItem.getType() != armorType) continue;

                ResourceLocation armorId = ForgeRegistries.ITEMS.getKey(item);
                if (armorId == null) continue;
                if (ToolExclusionConfig.isExcluded(slotName, armorId.toString())) continue;

                armor.add(armorItem);
            }
            return armor;
        });
    }

    /**
     * Returns all TC ranged weapons matching the given type ("bow" or "crossbow"), excluding
     * items in {@link ToolExclusionConfig}. Results are cached per type name.
     */
    public static List<IModifiable> getEligibleRanged(String rangedType) {
        return RANGED_CACHE.computeIfAbsent(rangedType, k -> {
            Class<?> targetClass = switch (rangedType) {
                case "bow"      -> ModifiableBowItem.class;
                case "crossbow" -> ModifiableCrossbowItem.class;
                default         -> null;
            };
            if (targetClass == null) return List.of();

            List<IModifiable> ranged = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS) {
                if (!targetClass.isInstance(item)) continue;
                if (!(item instanceof IModifiable modifiable)) continue;

                ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(item);
                if (itemId != null && ToolExclusionConfig.isExcluded(rangedType, itemId.toString())) continue;

                ranged.add(modifiable);
            }
            return ranged;
        });
    }

    // ── Shield scanning (stat-type-based, not instanceof) ───────────────

    private static final MaterialStatsId SHIELD_CORE_STAT = new MaterialStatsId("tconstruct", "shield_core");

    /**
     * Returns all TC shields (items with shield_core stat type), excluding items
     * in {@link ToolExclusionConfig}. Results are cached per type name.
     */
    public static List<IModifiable> getEligibleShields(String shieldType) {
        return SHIELD_CACHE.computeIfAbsent(shieldType, k -> {
            List<IModifiable> shields = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS) {
                if (!(item instanceof IModifiable modifiable)) continue;
                // Laminar armor has shield_core as a sub-part — exclude armor items
                if (item instanceof ModifiableArmorItem) continue;

                ToolDefinition def = modifiable.getToolDefinition();
                if (!def.isDataLoaded()) continue;

                if (!ToolMaterialHook.stats(def).contains(SHIELD_CORE_STAT)) continue;

                ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(item);
                if (itemId != null && ToolExclusionConfig.isExcluded(shieldType, itemId.toString())) continue;

                shields.add(modifiable);
            }
            return shields;
        });
    }

    // ── Fishing rod scanning (tag-based via Forge convention) ──

    /**
     * Returns all TC fishing rods, excluding items in {@link ToolExclusionConfig}.
     * Results are cached per type name.
     *
     * <p>Detection iterates {@link Tags.Items#TOOLS_FISHING_RODS} (the Forge convention tag
     * that TC and any addon fishing rod populate). The {@code instanceof IModifiable} filter
     * excludes vanilla {@code minecraft:fishing_rod} from the result.
     *
     * <p>This avoids action-based detection entirely: {@code FISHING_ROD_CAST} is granted by
     * TC's {@code fishing} trait modifier — only visible after {@code rebuildStats()} populates
     * {@code tic_modifiers} — so action checks would require building a ToolStack per item.
     * The tag is the same mechanism TC uses internally to enumerate fishing rods (see
     * {@code ItemTagProvider.java:339}).
     */
    public static List<IModifiable> getEligibleFishingRods(String fishingRodType) {
        return FISHING_ROD_CACHE.computeIfAbsent(fishingRodType, k -> {
            List<IModifiable> rods = new ArrayList<>();
            for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(Tags.Items.TOOLS_FISHING_RODS)) {
                Item item = holder.value();
                if (!(item instanceof IModifiable modifiable)) continue;

                ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(item);
                if (itemId != null && ToolExclusionConfig.isExcluded(fishingRodType, itemId.toString())) continue;

                rods.add(modifiable);
            }
            return rods;
        });
    }
}
