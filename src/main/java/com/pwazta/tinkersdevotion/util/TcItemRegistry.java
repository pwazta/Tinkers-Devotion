package com.pwazta.tinkersdevotion.util;

import com.pwazta.tinkersdevotion.config.ToolExclusionConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.Tags;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.registries.ForgeRegistries;
import slimeknights.tconstruct.common.TinkerTags;
import slimeknights.tconstruct.library.tools.item.IModifiable;
import slimeknights.tconstruct.library.tools.item.armor.ModifiableArmorItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Cached tag-based scanner for eligible TC tools, armor, ranged weapons, shields, and fishing rods.
 *
 * <p>All methods iterate a TC- or Forge-populated item tag and apply an {@code instanceof IModifiable}
 * filter to exclude vanilla items. This is {@code O(M)} (M = tag size, ~30–80 with addons) versus
 * the prior {@code O(N)} full-registry scan.
 *
 * <p>Tag choice per type (verified TC-populated):
 * <ul>
 *   <li>Tools: iterate {@link TinkerTags.Items#MODIFIABLE}, matched by Forge {@link ToolAction} or
 *       vanilla-tag membership (see {@link ToolType}). The action arm catches addon tools, which tag
 *       only into TC tags — not the vanilla {@code minecraft:<type>} tags TC populates for its own.</li>
 *   <li>Ranged: {@link TinkerTags.Items#LONGBOWS}/{@code CROSSBOWS} — also catches addon launchers
 *       that don't extend {@code ModifiableBowItem}/{@code ModifiableCrossbowItem} directly
 *       (e.g. Tinkers-Thinking's repeating crossbow).</li>
 *   <li>Shields: {@link TinkerTags.Items#SHIELDS}.</li>
 *   <li>Armor: {@link TinkerTags.Items#HELMETS}/{@code CHESTPLATES}/{@code LEGGINGS}/{@code BOOTS}.</li>
 *   <li>Fishing rods: {@link Tags.Items#TOOLS_FISHING_RODS} (Forge convention; equivalent set).</li>
 * </ul>
 *
 * <p>Caches are cleared on config / material reload via {@link #clearCaches()}.
 */
public final class TcItemRegistry {

    private TcItemRegistry() {}

    /**
     * Detection signal for a vanilla tool type. An item matches if it can perform the type's Forge
     * {@link ToolAction} (addon tools declare this in their definition) or is in the vanilla item tag.
     * The tag arm backfills TC tools whose type lives in the tag, not an action — e.g. the scythe, in
     * {@code minecraft:hoes} but with no {@code HOE_DIG} ({@code tilling} grants {@code HOE_TILL}, which
     * we don't key on as it's a player-applied modifier).
     */
    public record ToolType(ToolAction action, TagKey<Item> vanillaTag) {
        public boolean matches(ItemStack stack) {
            return stack.canPerformAction(action) || stack.is(vanillaTag);
        }
    }

    /** Per–tool-action detection signal (Forge ToolAction + vanilla tag). */
    private static final Map<String, ToolType> TOOL_TYPES = Map.of(
        "sword",   new ToolType(ToolActions.SWORD_DIG,   ItemTags.SWORDS),
        "pickaxe", new ToolType(ToolActions.PICKAXE_DIG, ItemTags.PICKAXES),
        "axe",     new ToolType(ToolActions.AXE_DIG,     ItemTags.AXES),
        "shovel",  new ToolType(ToolActions.SHOVEL_DIG,  ItemTags.SHOVELS),
        "hoe",     new ToolType(ToolActions.HOE_DIG,     ItemTags.HOES)
    );

    /** Per-slot armor tag (TC populates these via {@code addArmorTags}). */
    private static final Map<ArmorItem.Type, TagKey<Item>> ARMOR_TAGS = Map.of(
        ArmorItem.Type.HELMET,     TinkerTags.Items.HELMETS,
        ArmorItem.Type.CHESTPLATE, TinkerTags.Items.CHESTPLATES,
        ArmorItem.Type.LEGGINGS,   TinkerTags.Items.LEGGINGS,
        ArmorItem.Type.BOOTS,      TinkerTags.Items.BOOTS
    );

    /** Per–ranged-type tag. */
    private static final Map<String, TagKey<Item>> RANGED_TAGS = Map.of(
        "bow",      TinkerTags.Items.LONGBOWS,
        "crossbow", TinkerTags.Items.CROSSBOWS
    );

    private static final Map<String, List<IModifiable>> TOOL_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, List<IModifiable>> ARMOR_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, List<IModifiable>> RANGED_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, List<IModifiable>> SHIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, List<IModifiable>> FISHING_ROD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, List<IModifiable>> FLINT_AND_STEEL_CACHE = new ConcurrentHashMap<>();

    /** Clears all cached scan results. Called on config/material reload. */
    public static void clearCaches() {
        TOOL_CACHE.clear();
        ARMOR_CACHE.clear();
        RANGED_CACHE.clear();
        SHIELD_CACHE.clear();
        FISHING_ROD_CACHE.clear();
        FLINT_AND_STEEL_CACHE.clear();
    }

    /**
     * Returns all TC tools matching the given type (sword/pickaxe/axe/shovel/hoe) by ToolAction or
     * vanilla tag, excluding items in {@link ToolExclusionConfig}. Cached per type name.
     */
    public static List<IModifiable> getEligibleTools(String actionName) {
        return TOOL_CACHE.computeIfAbsent(actionName, k -> {
            ToolType type = TOOL_TYPES.get(actionName);
            if (type == null) return List.of();
            // Scan the umbrella (addon tools tag into it); bare ItemStack ⇒ definition-level actions only.
            return collect(TinkerTags.Items.MODIFIABLE, actionName, item -> type.matches(new ItemStack(item)));
        });
    }

    /** Returns the detection signal for a tool type, or null for unknown names. Used by {@link com.pwazta.tinkersdevotion.recipe.ToolMode} for runtime stack matching. */
    public static ToolType getToolType(String actionName) {
        return TOOL_TYPES.get(actionName);
    }

    /**
     * Returns all TC armor pieces matching the given slot and any of the allowed armor set
     * prefixes, excluding items in {@link ToolExclusionConfig}. Cached per (sets, slot).
     *
     * @param sets allowed armor set prefixes with full namespace (e.g., {@code "tconstruct:plate"},
     *             {@code "tinkers_things:laminar"})
     */
    public static List<IModifiable> getEligibleArmor(ArmorItem.Type armorType, List<String> sets, String slotName) {
        return ARMOR_CACHE.computeIfAbsent(sets + ":" + slotName,
            k -> collectArmor(armorType, sets, slotName));
    }

    /**
     * Returns all TC armor pieces in the given slot regardless of set, excluding items in
     * {@link ToolExclusionConfig}. Used by recipe JEI display (any set at matching tier).
     */
    public static List<IModifiable> getAllEligibleArmor(ArmorItem.Type armorType, String slotName) {
        return ARMOR_CACHE.computeIfAbsent("all:" + slotName,
            k -> collectArmor(armorType, null, slotName));
    }

    /**
     * Returns all TC ranged weapons matching the given type ("bow" or "crossbow"), excluding
     * items in {@link ToolExclusionConfig}. Cached per type.
     */
    public static List<IModifiable> getEligibleRanged(String rangedType) {
        return RANGED_CACHE.computeIfAbsent(rangedType, k -> {
            TagKey<Item> tag = RANGED_TAGS.get(rangedType);
            if (tag == null) return List.of();
            return collect(tag, rangedType, null);
        });
    }

    /**
     * Returns all TC shields, excluding items in {@link ToolExclusionConfig}. Cached per type.
     *
     * <p>The {@code !ModifiableArmorItem} guard is defensive — {@link TinkerTags.Items#SHIELDS}
     * shouldn't contain armor items, but laminar armor has {@code shield_core} as a sub-part stat
     * type and could conceivably leak in via addon mistagging.
     */
    public static List<IModifiable> getEligibleShields(String shieldType) {
        return SHIELD_CACHE.computeIfAbsent(shieldType, k -> collect(
            TinkerTags.Items.SHIELDS,
            shieldType,
            item -> !(item instanceof ModifiableArmorItem)
        ));
    }

    /**
     * Returns all TC fishing rods, excluding items in {@link ToolExclusionConfig}. Cached per type.
     *
     * <p>Uses the Forge convention tag {@link Tags.Items#TOOLS_FISHING_RODS} — equivalent set to
     * {@link TinkerTags.Items#FISHING_RODS} since TC populates both.
     */
    public static List<IModifiable> getEligibleFishingRods(String fishingRodType) {
        return FISHING_ROD_CACHE.computeIfAbsent(fishingRodType, k -> collect(
            Tags.Items.TOOLS_FISHING_RODS,
            fishingRodType,
            null
        ));
    }

    /**
     * Returns the resolved TC items for the given flint-and-steel type from the supplied id list,
     * excluding items in {@link ToolExclusionConfig}. Cached per type.
     *
     * <p>No tag exists for the flint-and-steel category in TC ({@code flint_and_brick} carries only
     * generic tags), so the strategy/mode pass the hardcoded id list from
     * {@link com.pwazta.tinkersdevotion.loot.VanillaItemMappings.FlintAndSteelInfo}.
     */
    public static List<IModifiable> getEligibleFlintAndSteel(String flintAndSteelType, List<String> tcItemIds) {
        return FLINT_AND_STEEL_CACHE.computeIfAbsent(flintAndSteelType, k -> {
            List<IModifiable> result = new ArrayList<>(tcItemIds.size());
            for (String id : tcItemIds) {
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
                if (!(item instanceof IModifiable modifiable)) continue;
                if (ToolExclusionConfig.isExcluded(flintAndSteelType, id)) continue;
                result.add(modifiable);
            }
            return result;
        });
    }

    /** Iterates a tag, requires {@link IModifiable} (excludes vanilla), applies optional extra filter + exclusion config. */
    private static List<IModifiable> collect(TagKey<Item> tag, String exclusionKey, Predicate<Item> extraFilter) {
        List<IModifiable> result = new ArrayList<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
            Item item = holder.value();
            if (!(item instanceof IModifiable modifiable)) continue;
            if (extraFilter != null && !extraFilter.test(item)) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id != null && ToolExclusionConfig.isExcluded(exclusionKey, id.toString())) continue;
            result.add(modifiable);
        }
        return result;
    }

    /**
     * Tag-iteration variant of {@link #collect} for armor: keeps the {@code instanceof
     * ModifiableArmorItem} cast, applies the optional set-prefix filter, and skips redundant
     * {@code getType()} matching since the slot tag already enforces it.
     */
    private static List<IModifiable> collectArmor(ArmorItem.Type armorType, List<String> sets, String slotName) {
        TagKey<Item> tag = ARMOR_TAGS.get(armorType);
        if (tag == null) return List.of();

        List<IModifiable> result = new ArrayList<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
            Item item = holder.value();
            if (!(item instanceof ModifiableArmorItem armorItem)) continue;

            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null) continue;
            String idStr = id.toString();
            if (ToolExclusionConfig.isExcluded(slotName, idStr)) continue;

            if (sets != null && !matchesSet(idStr, sets)) continue;

            result.add(armorItem);
        }
        return result;
    }

    private static boolean matchesSet(String idStr, List<String> sets) {
        for (String set : sets) {
            if (idStr.startsWith(set + "_")) return true;
        }
        return false;
    }
}
