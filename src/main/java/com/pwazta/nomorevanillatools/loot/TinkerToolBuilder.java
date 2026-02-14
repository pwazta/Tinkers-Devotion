package com.pwazta.nomorevanillatools.loot;

import com.mojang.logging.LogUtils;
import com.pwazta.nomorevanillatools.config.MaterialMappingConfig;
import com.pwazta.nomorevanillatools.config.ToolExclusionConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import slimeknights.tconstruct.library.materials.IMaterialRegistry;
import slimeknights.tconstruct.library.materials.MaterialRegistry;
import slimeknights.tconstruct.library.materials.definition.IMaterial;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.materials.stats.MaterialStatsId;
import slimeknights.tconstruct.library.tools.definition.ToolDefinition;
import slimeknights.tconstruct.library.tools.definition.module.ToolHooks;
import slimeknights.tconstruct.library.tools.definition.module.material.ToolMaterialHook;
import slimeknights.tconstruct.library.tools.item.IModifiable;
import slimeknights.tconstruct.library.tools.item.IModifiableDisplay;
import slimeknights.tconstruct.library.tools.item.armor.ModifiableArmorItem;
import slimeknights.tconstruct.library.tools.nbt.MaterialNBT;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds randomized Tinker's Construct tools and armor for loot replacement.
 * Selects materials using weighted algorithms per part slot.
 *
 * Shared by VanillaLootReplacer (GLM) and MobEquipmentReplacer (spawn event).
 */
public class TinkerToolBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ── Material selection weights ──────────────────────────────────────

    /** Probability of selecting the canonical (first-in-config) head/plating material. */
    private static final float HEAD_CANONICAL_WEIGHT = 0.85f;

    /** Probability threshold for selecting the basic (lowest tier) material for other parts. */
    private static final float OTHER_BASIC_WEIGHT = 0.50f;

    /** Cumulative threshold for basic + same-as-head selection for other parts. (0.80 = 50% basic + 30% same-as-head) */
    private static final float OTHER_SAME_AS_HEAD_WEIGHT = 0.80f;

    /** Maximum IMaterial.getTier() allowed for armor inner parts. Caps at diamond-level to prevent overpowered inner materials. */
    private static final int ARMOR_INNER_MAX_TIER = 3;

    // ── Caches (ConcurrentHashMap, consistent with codebase pattern) ─────

    /** Eligible TC tools per action name. Cleared on config reload. */
    private static final Map<String, List<Item>> TOOL_CACHE = new ConcurrentHashMap<>();

    /** Eligible TC armor per slot name. Cleared on config reload. */
    private static final Map<String, List<Item>> ARMOR_CACHE = new ConcurrentHashMap<>();

    /** Compatible materials per stat type. Cleared on materials reload. */
    private static final Map<MaterialStatsId, List<IMaterial>> MATERIAL_CACHE = new ConcurrentHashMap<>();

    /** Clears all caches. Called from MaterialMappingConfig and ToolExclusionConfig reload paths. */
    public static void clearCaches() {
        TOOL_CACHE.clear();
        ARMOR_CACHE.clear();
        MATERIAL_CACHE.clear();
    }

    // ── Main entry point ─────────────────────────────────────────────────

    /**
     * Attempts to replace a vanilla tool/armor ItemStack with a TC equivalent.
     *
     * @param original the original vanilla ItemStack
     * @param random   random source for material selection
     * @return a TC replacement ItemStack, or null if no replacement applies
     */
    public static @Nullable ItemStack tryReplace(ItemStack original, RandomSource random) {
        if (original.isEmpty()) return null;

        Item item = original.getItem();

        // Check tools
        VanillaItemMappings.ToolInfo toolInfo = VanillaItemMappings.getToolInfo(item);
        if (toolInfo != null) return buildRandomTool(toolInfo.toolType(), toolInfo.tier(), original, random);

        // Check armor
        VanillaItemMappings.ArmorInfo armorInfo = VanillaItemMappings.getArmorInfo(item);
        if (armorInfo != null) return buildRandomArmor(armorInfo.slot(), armorInfo.tier(), original, random);

        return null;
    }

    // ── Tool building ────────────────────────────────────────────────────

    private static @Nullable ItemStack buildRandomTool(String toolType, String tier, ItemStack original, RandomSource random) {
        ToolAction action = VanillaItemMappings.getToolAction(toolType);
        if (action == null) return null;

        List<Item> eligible = getEligibleTools(action, toolType);
        if (eligible.isEmpty()) return null;

        Item selected = eligible.get(random.nextInt(eligible.size()));
        if (!(selected instanceof IModifiable modifiable)) return null;

        return buildWithMaterials(modifiable, tier, original, random, false);
    }

    // ── Armor building ───────────────────────────────────────────────────

    private static @Nullable ItemStack buildRandomArmor(String slot, String tier, ItemStack original, RandomSource random) {
        ArmorItem.Type armorType = VanillaItemMappings.getArmorType(slot);
        if (armorType == null) return null;

        List<Item> eligible = getEligibleArmor(armorType, slot);
        if (eligible.isEmpty()) return null;

        Item selected = eligible.get(random.nextInt(eligible.size()));
        if (!(selected instanceof IModifiable modifiable)) return null;

        return buildWithMaterials(modifiable, tier, original, random, true);
    }

    // ── Shared build logic ───────────────────────────────────────────────

    private static @Nullable ItemStack buildWithMaterials(IModifiable modifiable, String tier, ItemStack original, RandomSource random, boolean isArmor) {
        try {
            ToolDefinition definition = modifiable.getToolDefinition();
            if (!definition.isDataLoaded()) return null;

            List<MaterialStatsId> statTypes = ToolMaterialHook.stats(definition);
            if (statTypes.isEmpty()) {
                LOGGER.warn("No material stats for tool definition {}, skipping loot replacement",
                    ForgeRegistries.ITEMS.getKey((Item) modifiable));
                return null;
            }

            // Select materials for each part
            MaterialNBT.Builder materialsBuilder = MaterialNBT.builder();

            // Select head/plating (index 0) — tier-constrained
            MaterialVariantId headMaterial = selectHeadMaterial(tier, random, isArmor);
            if (headMaterial == null) return null;
            materialsBuilder.add(headMaterial);

            // Get TC tier of head material for other-parts filtering
            IMaterial headMat = MaterialRegistry.getInstance().getMaterial(headMaterial.getId());
            int headTcTier = headMat.getTier();

            // Select other parts (index 1+)
            for (int i = 1; i < statTypes.size(); i++) {
                MaterialStatsId statType = statTypes.get(i);
                MaterialVariantId partMaterial;

                if (isArmor) partMaterial = selectArmorInnerMaterial(statType, headTcTier, random);
                else partMaterial = selectOtherPartMaterial(statType, headTcTier, headMaterial, random);
                
                if (partMaterial == null) return null;
                materialsBuilder.add(partMaterial);
            }

            // Build the tool
            ToolStack toolStack = ToolStack.createTool((Item) modifiable, definition, materialsBuilder.build());
            toolStack.rebuildStats();

            // Transfer damage proportionally
            ItemStack result = toolStack.createStack();
            transferDamage(original, result);

            // TODO: Enchantment -> TC modifier conversion (P2)
            // Currently enchantments on the original vanilla item are dropped.
            // TC tools use modifiers, not enchantments. Future: map common enchantments
            // to TC modifier equivalents and apply via ToolStack modifier API.
            // See docs/plans/2026-02-14-loot-integration-design.md "Future Tasks"

            return result;
        } catch (Exception e) {
            LOGGER.warn("Failed to build TC replacement for loot item: {}", e.getMessage());
            return null;
        }
    }

    // ── Head / Plating selection (index 0) ───────────────────────────────

    /**
     * Selects head/plating material using weighted algorithm:
     * 85% canonical (first in config list), 15% random from tier pool.
     */
    private static @Nullable MaterialVariantId selectHeadMaterial(String tier, RandomSource random, boolean isArmor) {
        Set<String> materials = isArmor
            ? MaterialMappingConfig.getArmorMaterialsForTier(tier)
            : MaterialMappingConfig.getMaterialsForTier(tier);
        if (materials == null || materials.isEmpty()) return null;

        String selectedId;
        if (random.nextFloat() < HEAD_CANONICAL_WEIGHT) {
            // Canonical: first in config list (LinkedHashSet preserves insertion order)
            selectedId = materials.iterator().next();
        } else {
            // Random from tier pool
            selectedId = materials.stream().skip(random.nextInt(materials.size())).findFirst().orElse(null);
        }
        if (selectedId == null) return null;

        return MaterialVariantId.tryParse(selectedId);
    }

    // ── Tool other parts (index 1+) — same TC tier or lower ──────────────

    /**
     * Selects material for a non-head tool part using weighted algorithm:
     * 50% basic (lowest tier), 30% same as head, 20% random (same tier or lower).
     *
     * Uses IMaterial.getTier() (int 0-4) for filtering, NOT HeadMaterialStats.tier().
     */
    private static @Nullable MaterialVariantId selectOtherPartMaterial(MaterialStatsId statType,
            int headTcTier, MaterialVariantId headMaterial, RandomSource random) {
        List<IMaterial> compatible = getCompatibleMaterials(statType);
        List<IMaterial> filtered = compatible.stream()
            .filter(mat -> mat.getTier() <= headTcTier)
            .toList();
        if (filtered.isEmpty()) return null;

        // Find basic (lowest tier) material
        IMaterial basic = filtered.get(0);
        for (IMaterial mat : filtered) {
            if (mat.getTier() < basic.getTier()) basic = mat;
        }

        // Check if head material is compatible with this stat type
        boolean headCompatible = compatible.stream()
            .anyMatch(mat -> mat.getIdentifier().equals(headMaterial.getId()));

        float roll = random.nextFloat();
        if (roll < OTHER_BASIC_WEIGHT) {
            return MaterialVariantId.create(basic.getIdentifier(), "");
        } else if (roll < OTHER_SAME_AS_HEAD_WEIGHT) {
            // Same as head if compatible, else basic
            if (headCompatible) return headMaterial;
            return MaterialVariantId.create(basic.getIdentifier(), "");
        } else {
            IMaterial selected = filtered.get(random.nextInt(filtered.size()));
            return MaterialVariantId.create(selected.getIdentifier(), "");
        }
    }

    // ── Armor inner parts (index 1+) — tier-filtered, capped at diamond ──

    /**
     * Selects material for armor inner part (maille, shield_core, etc.).
     * Uniform random from compatible materials, filtered to head tier or lower,
     * hard-capped at ARMOR_INNER_MAX_TIER (3 = diamond) to prevent overpowered inner materials.
     */
    private static @Nullable MaterialVariantId selectArmorInnerMaterial(MaterialStatsId statType, int headTcTier, RandomSource random) {
        int maxTier = Math.min(headTcTier, ARMOR_INNER_MAX_TIER);
        List<IMaterial> compatible = getCompatibleMaterials(statType);
        List<IMaterial> filtered = compatible.stream()
            .filter(mat -> mat.getTier() <= maxTier)
            .toList();
        if (filtered.isEmpty()) return null;

        IMaterial selected = filtered.get(random.nextInt(filtered.size()));
        return MaterialVariantId.create(selected.getIdentifier(), "");
    }

    // ── Damage transfer ──────────────────────────────────────────────────

    /** Transfers damage proportionally from original to replacement. */
    private static void transferDamage(ItemStack original, ItemStack replacement) {
        if (!original.isDamaged() || original.getMaxDamage() <= 0 || replacement.getMaxDamage() <= 0) return;

        float ratio = (float) original.getDamageValue() / original.getMaxDamage();
        int newDamage = Math.min((int) (ratio * replacement.getMaxDamage()), replacement.getMaxDamage() - 1);
        replacement.setDamageValue(newDamage);
    }

    // ── Eligible item scanning (cached) ──────────────────────────────────

    /** Gets eligible TC tools for a given ToolAction, with exclusion filtering. */
    private static List<Item> getEligibleTools(ToolAction action, String actionName) {
        return TOOL_CACHE.computeIfAbsent(actionName, k -> {
            List<Item> tools = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS) {
                if (!(item instanceof IModifiableDisplay display)) continue;
                if (!(item instanceof IModifiable)) continue;
                ToolDefinition definition = display.getToolDefinition();
                if (!definition.isDataLoaded()) continue;

                // Check if this tool supports the required action
                ItemStack renderStack = display.getRenderTool();
                boolean supportsAction = definition.getData().getHook(ToolHooks.TOOL_ACTION)
                    .canPerformAction(ToolStack.from(renderStack), action);
                if (!supportsAction) continue;

                // Check exclusion list
                ResourceLocation toolId = ForgeRegistries.ITEMS.getKey(item);
                if (toolId != null && ToolExclusionConfig.isExcluded(actionName, toolId.toString())) continue;

                tools.add(item);
            }
            return tools;
        });
    }

    /** Gets eligible TC armor for a given slot, with exclusion filtering. */
    private static List<Item> getEligibleArmor(ArmorItem.Type armorType, String slotName) {
        return ARMOR_CACHE.computeIfAbsent(slotName, k -> {
            List<Item> armor = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS) {
                if (!(item instanceof ModifiableArmorItem armorItem)) continue;
                if (armorItem.getType() != armorType) continue;

                ResourceLocation armorId = ForgeRegistries.ITEMS.getKey(item);
                if (armorId != null && ToolExclusionConfig.isExcluded(slotName, armorId.toString())) continue;

                armor.add(item);
            }
            return armor;
        });
    }

    // ── Compatible materials per stat type (cached) ──────────────────────

    /** Gets all materials compatible with a given stat type. */
    private static List<IMaterial> getCompatibleMaterials(MaterialStatsId statType) {
        return MATERIAL_CACHE.computeIfAbsent(statType, k -> {
            IMaterialRegistry registry = MaterialRegistry.getInstance();
            List<IMaterial> compatible = new ArrayList<>();
            for (IMaterial material : registry.getAllMaterials()) {
                if (registry.getMaterialStats(material.getIdentifier(), statType).isPresent()) {
                    compatible.add(material);
                }
            }
            return compatible;
        });
    }
}
