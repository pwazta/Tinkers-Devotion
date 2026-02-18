package com.pwazta.nomorevanillatools.util;

import com.pwazta.nomorevanillatools.config.ToolExclusionConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.registries.ForgeRegistries;
import slimeknights.tconstruct.library.tools.definition.ToolDefinition;
import slimeknights.tconstruct.library.tools.definition.module.ToolHooks;
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

    /** Clears all cached scan results. Called on config/material reload. */
    public static void clearCaches() {
        TOOL_CACHE.clear();
        ARMOR_CACHE.clear();
        RANGED_CACHE.clear();
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
     * Returns all TC armor pieces matching the given {@link ArmorItem.Type}, excluding items in
     * {@link ToolExclusionConfig}. Results are cached per slot name.
     */
    public static List<IModifiable> getEligibleArmor(ArmorItem.Type armorType, String slotName) {
        return ARMOR_CACHE.computeIfAbsent(slotName, k -> {
            List<IModifiable> armor = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS) {
                if (!(item instanceof ModifiableArmorItem armorItem)) continue;
                if (armorItem.getType() != armorType) continue;

                ResourceLocation armorId = ForgeRegistries.ITEMS.getKey(item);
                if (armorId != null && ToolExclusionConfig.isExcluded(slotName, armorId.toString())) continue;

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
}
