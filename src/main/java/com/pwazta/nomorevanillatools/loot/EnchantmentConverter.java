package com.pwazta.nomorevanillatools.loot;

import com.mojang.logging.LogUtils;
import com.pwazta.nomorevanillatools.Config;
import com.pwazta.nomorevanillatools.config.ModifierSkipListConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import slimeknights.tconstruct.library.modifiers.ModifierEntry;
import slimeknights.tconstruct.library.modifiers.ModifierHooks;
import slimeknights.tconstruct.library.modifiers.ModifierId;
import slimeknights.tconstruct.library.modifiers.ModifierManager;
import slimeknights.tconstruct.library.recipe.TinkerRecipeTypes;
import slimeknights.tconstruct.library.recipe.modifiers.adding.AbstractModifierRecipe;
import slimeknights.tconstruct.library.tools.SlotType;
import slimeknights.tconstruct.library.tools.nbt.ModifierNBT;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Converts vanilla enchantment "power budget" into randomized TC modifiers.
 *
 * Called from {@link TinkerToolBuilder#buildFromMaterials} when replacing vanilla
 * loot/mob equipment items with TC equivalents. The conversion uses a three-layer
 * weighted selection algorithm:
 * <ol>
 *   <li>Compatibility filter — only modifiers whose recipe accepts the tool type</li>
 *   <li>Specificity weighting — specialist modifiers (fewer category overlaps) weighted higher</li>
 *   <li>Level-up preference — configurable chance (default 50%) to level up an existing modifier</li>
 * </ol>
 *
 * Modifier pool is lazily built from TC tinker station recipes on first use, cached
 * for the server lifecycle, and cleared on materials reload or config changes.
 */
public class EnchantmentConverter {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ── Budget constants ────────────────────────────────────────────────

    /** Enchant levels → budget scaling factor. */
    private static final double POWER_MULTIPLIER = 4.0;
    /** Maximum modifier budget (caps total modifiers applied). */
    private static final int MAX_BUDGET = 7;
    /** Maximum bonus slot modifiers (writable, harmonious, recapitated, forecast). */
    private static final int MAX_BONUS = 4;
    /** Minimum budget required before an ability-slot modifier can be picked. */
    private static final int ABILITY_BUDGET_THRESHOLD = 4;

    // ── Bonus slot modifiers (applied as slot expansion, excluded from random pool) ──

    private static final List<ModifierId> BONUS_SLOT_ORDER = List.of(
        new ModifierId("tconstruct", "writable"),
        new ModifierId("tconstruct", "harmonious"),
        new ModifierId("tconstruct", "recapitated"),
        new ModifierId("tconstruct", "forecast")
    );

    private static final Set<ModifierId> BONUS_MODIFIERS = Set.copyOf(BONUS_SLOT_ORDER);

    // ── Tool category tags for specificity-based weighting ────────────────

    private static final List<TagKey<Item>> CATEGORY_TAGS = List.of(
        TagKey.create(Registries.ITEM, new ResourceLocation("tconstruct", "modifiable/melee")),
        TagKey.create(Registries.ITEM, new ResourceLocation("tconstruct", "modifiable/harvest")),
        TagKey.create(Registries.ITEM, new ResourceLocation("tconstruct", "modifiable/armor")),
        TagKey.create(Registries.ITEM, new ResourceLocation("tconstruct", "modifiable/ranged"))
    );

    // ── Pool cache (volatile + double-checked locking) ──────────────────

    private static volatile Map<ModifierId, ModifierPoolEntry> poolCache;

    // ── Inner record ────────────────────────────────────────────────────

    /** Cached info about a modifier available from TC recipes, with pre-computed specificity weight. */
    record ModifierPoolEntry(ModifierId id, SlotType slotType, int maxLevel, Set<Item> compatibleTools, double weight) {}

    // ═══════════════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Converts enchantments on the original vanilla item into TC modifiers on the tool stack.
     * Must be called AFTER {@code rebuildStats()} and BEFORE {@code createStack()}.
     *
     * @param toolStack the TC tool being built (mutable, will be modified)
     * @param original  the original vanilla item whose enchantments drive the budget
     * @param random    random source for weighted selection
     */
    public static void applyModifiers(ToolStack toolStack, ItemStack original, RandomSource random) {
        // 1. Calculate budget from enchantment levels
        int budget = calculateBudget(original);
        if (budget == 0) return;

        // 2. Track slot counts arithmetically (no intermediate rebuildStats calls).
        //    Initial rebuildStats already happened in buildFromMaterials before this call.
        int baseFreeUpgrades = toolStack.getFreeSlots(SlotType.UPGRADE);
        int freeAbilities = toolStack.getFreeSlots(SlotType.ABILITY);
        int freeDefense = toolStack.getFreeSlots(SlotType.DEFENSE);

        // 3. Calculate bonus slot modifiers needed.
        //    Each bonus grants exactly +1 upgrade (verified: modifier_slot module, each_level: 1).
        int totalFreeSlots = baseFreeUpgrades + freeDefense + freeAbilities;
        int bonusNeeded = Math.max(0, Math.min(budget - totalFreeSlots, MAX_BONUS));
        int freeUpgrades = baseFreeUpgrades + bonusNeeded;

        // 4. Get compatible modifier pool (cached globally, filtered per tool).
        //    createStack() here is lightweight — item identity is all that matters for tag matching.
        ItemStack representative = toolStack.createStack();
        List<ModifierPoolEntry> pool = getCompatiblePool(representative);
        if (pool.isEmpty()) return;

        // 5. Build existing modifier levels map (traits + pre-existing upgrades)
        Map<ModifierId, Integer> existingLevels = new HashMap<>();
        for (ModifierEntry existing : toolStack.getModifierList())
            existingLevels.put(existing.getId(), existing.getLevel());

        // 6. Pick modifiers from weighted pool
        Map<ModifierId, Integer> picked = new LinkedHashMap<>();
        int upgradesUsed = 0;
        boolean abilityPicked = false;
        int defenseUsed = 0;

        for (int i = 0; i < budget; i++) {
            boolean upgradeAvail = upgradesUsed < freeUpgrades;
            boolean defenseAvail = defenseUsed < freeDefense;
            boolean abilityAvail = !abilityPicked && budget >= ABILITY_BUDGET_THRESHOLD && freeAbilities > 0;

            if (!upgradeAvail && !defenseAvail && !abilityAvail) break;

            // Level-up attempt (configurable chance, default 50%, only for upgrade/defense)
            if (!picked.isEmpty() && (upgradeAvail || defenseAvail) && random.nextFloat() < Config.levelUpChance) {
                ModifierId leveled = tryLevelUp(picked, pool, random, upgradeAvail, defenseAvail, existingLevels);
                if (leveled != null) {
                    picked.merge(leveled, 1, Integer::sum);
                    ModifierPoolEntry leveledEntry = findEntry(pool, leveled);
                    if (leveledEntry != null && leveledEntry.slotType() == SlotType.DEFENSE)
                        defenseUsed++;
                    else
                        upgradesUsed++;
                    continue;
                }
            }

            // Pick with bounded retry when picked slot type is full
            boolean applied = false;
            for (int attempt = 0; attempt < 3 && !applied; attempt++) {
                ModifierPoolEntry entry = weightedPick(pool, picked, random, existingLevels);
                if (entry == null) break;

                if (entry.slotType() == SlotType.UPGRADE && upgradeAvail) {
                    picked.merge(entry.id(), 1, Integer::sum);
                    upgradesUsed++;
                    applied = true;
                } else if (entry.slotType() == SlotType.DEFENSE && defenseAvail) {
                    picked.merge(entry.id(), 1, Integer::sum);
                    defenseUsed++;
                    applied = true;
                } else if (entry.slotType() == SlotType.ABILITY && abilityAvail) {
                    picked.put(entry.id(), 1);
                    abilityPicked = true;
                    applied = true;
                }
            }
        }

        if (picked.isEmpty() && bonusNeeded == 0) return;

        // 7. Batch ALL modifiers (bonus + picked) into single setUpgrades → single rebuildStats.
        ModifierNBT upgrades = toolStack.getUpgrades();
        for (int i = 0; i < bonusNeeded; i++) {
            upgrades = upgrades.withModifier(BONUS_SLOT_ORDER.get(i), 1);
        }
        for (Map.Entry<ModifierId, Integer> entry : picked.entrySet()) {
            upgrades = upgrades.withModifier(entry.getKey(), entry.getValue());
        }
        toolStack.setUpgrades(upgrades); // triggers single rebuildStats

        // 8. Deduct slots (bonus modifiers are slotless — no deduction for them).
        var persistentData = toolStack.getPersistentData();
        if (upgradesUsed > 0) persistentData.addSlots(SlotType.UPGRADE, -upgradesUsed);
        if (abilityPicked)    persistentData.addSlots(SlotType.ABILITY, -1);
        if (defenseUsed > 0)  persistentData.addSlots(SlotType.DEFENSE, -defenseUsed);
    }

    /** Invalidates the modifier pool cache. Called from {@link TinkerToolBuilder#clearCaches()}. */
    public static void clearCache() {
        poolCache = null;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Budget calculation
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Computes the modifier budget from the original item's total enchantment levels.
     * Formula: min(ceil(sqrt(totalLevels * 4.0)), 7)
     */
    static int calculateBudget(ItemStack original) {
        int totalLevels = 0;
        for (int level : EnchantmentHelper.getEnchantments(original).values()) {
            totalLevels += level;
        }
        if (totalLevels == 0) return 0;
        return Math.min((int) Math.ceil(Math.sqrt(totalLevels * POWER_MULTIPLIER)), MAX_BUDGET);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Pool management
    // ═══════════════════════════════════════════════════════════════════

    /** Returns the cached pool, building it lazily on first access (double-checked locking). */
    private static Map<ModifierId, ModifierPoolEntry> ensurePool() {
        Map<ModifierId, ModifierPoolEntry> cache = poolCache;
        if (cache != null) return cache;
        synchronized (EnchantmentConverter.class) {
            cache = poolCache;
            if (cache != null) return cache;
            cache = buildPool();
            poolCache = cache;
            return cache;
        }
    }

    /**
     * Scans all TC tinker station recipes for modifier recipes and builds the weighted pool.
     * Two-phase: first collects entries from recipes (merging duplicates), then computes
     * specificity-based weights from tool category overlap.
     */
    private static Map<ModifierId, ModifierPoolEntry> buildPool() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            LOGGER.debug("Server not available — modifier pool not built");
            return Map.of();
        }

        var recipeManager = server.getRecipeManager();
        Set<ModifierId> skipList = ModifierSkipListConfig.getSkipList();

        // Phase 1: Collect entries from recipes, merge duplicates (weight placeholder = 0.0)
        Map<ModifierId, ModifierPoolEntry> pool = new HashMap<>();

        for (var recipe : recipeManager.getAllRecipesFor(TinkerRecipeTypes.TINKER_STATION.get())) {
            if (!(recipe instanceof AbstractModifierRecipe modRecipe)) continue;

            ModifierEntry displayResult = modRecipe.getDisplayResult();
            if (displayResult == null) continue;
            ModifierId modId = displayResult.getId();

            // Skip excluded modifiers (hardcoded bonus + player-configurable skip list)
            if (BONUS_MODIFIERS.contains(modId) || skipList.contains(modId)) continue;

            // Skip slotless modifiers (no slot cost = special modifiers, not for random application)
            SlotType.SlotCount slotCount = modRecipe.getSlots();
            if (slotCount == null) continue;
            SlotType slotType = slotCount.type();
            if (slotType == null) continue;

            // Extract max level (default to 1 if zero or missing)
            int maxLevel = 1;
            var levelRange = modRecipe.getLevel();
            if (levelRange != null) {
                maxLevel = Math.max(levelRange.max(), 1);
            }

            // Get compatible tools from display recipe (empty = compatible with all tools)
            Set<Item> compatibleTools = modRecipe.getToolWithoutModifier().stream()
                .map(ItemStack::getItem)
                .collect(Collectors.toCollection(HashSet::new));

            // Deduplicate: keep highest maxLevel, merge compatible tool sets
            pool.merge(modId, new ModifierPoolEntry(modId, slotType, maxLevel, compatibleTools, 0.0),
                (existing, incoming) -> {
                    Set<Item> merged = new HashSet<>(existing.compatibleTools());
                    merged.addAll(incoming.compatibleTools());
                    return new ModifierPoolEntry(existing.id(), existing.slotType(),
                        Math.max(existing.maxLevel(), incoming.maxLevel()), merged, 0.0);
                });
        }

        // Phase 2: Compute specificity-based weights from category overlap
        Map<TagKey<Item>, Set<Item>> categorySets = buildCategorySets();
        Collection<Set<Item>> catValues = categorySets.values();
        double k = Config.modifierWeightFalloff;

        Map<ModifierId, ModifierPoolEntry> weighted = new HashMap<>();
        for (ModifierPoolEntry entry : pool.values()) {
            int count = countCategoryOverlap(entry.compatibleTools(), catValues);
            double weight = 1.0 / Math.pow(count, k);
            weighted.put(entry.id(), new ModifierPoolEntry(
                entry.id(), entry.slotType(), entry.maxLevel(), entry.compatibleTools(), weight));
        }

        LOGGER.info("Built modifier pool with {} entries from TC recipes", weighted.size());
        return Collections.unmodifiableMap(weighted);
    }

    /** Filters the global pool to modifiers compatible with the given tool. */
    private static List<ModifierPoolEntry> getCompatiblePool(ItemStack representative) {
        Map<ModifierId, ModifierPoolEntry> pool = ensurePool();
        if (pool.isEmpty()) return List.of();

        List<ModifierPoolEntry> compatible = new ArrayList<>();
        for (ModifierPoolEntry entry : pool.values()) {
            SlotType st = entry.slotType();
            if (st != SlotType.UPGRADE && st != SlotType.ABILITY && st != SlotType.DEFENSE) continue;
            if (!entry.compatibleTools().isEmpty() && !entry.compatibleTools().contains(representative.getItem())) continue;
            compatible.add(entry);
        }
        return compatible;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Specificity-based weighting
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Builds item sets for each tool category tag in a single registry pass.
     * Called once per pool build — category sets are used to compute weights, then GC'd.
     */
    private static Map<TagKey<Item>, Set<Item>> buildCategorySets() {
        Map<TagKey<Item>, Set<Item>> result = new HashMap<>();
        for (TagKey<Item> tag : CATEGORY_TAGS) result.put(tag, new HashSet<>());

        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            var holder = item.builtInRegistryHolder();
            for (TagKey<Item> tag : CATEGORY_TAGS)
                if (holder.is(tag)) result.get(tag).add(item);
        }
        return result;
    }

    /**
     * Counts how many of TC's 4 tool category tags the modifier's compatible tools overlap with.
     * Empty compatibleTools (universal modifier) → max count (all categories).
     * Floor of 1 prevents division by zero in weight formula.
     */
    private static int countCategoryOverlap(Set<Item> compatibleTools, Collection<Set<Item>> categorySets) {
        if (compatibleTools.isEmpty()) return categorySets.size();
        int count = 0;
        for (Set<Item> catSet : categorySets)
            if (!Collections.disjoint(compatibleTools, catSet)) count++;
        return Math.max(count, 1);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Selection algorithms
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Weighted random selection from the compatible pool.
     * Uses pre-computed specificity weights. Excludes modifiers already at their level cap.
     */
    private static @Nullable ModifierPoolEntry weightedPick(List<ModifierPoolEntry> pool,
            Map<ModifierId, Integer> alreadyPicked, RandomSource random,
            Map<ModifierId, Integer> existingLevels) {
        double totalWeight = 0;
        double[] weights = new double[pool.size()];

        for (int i = 0; i < pool.size(); i++) {
            ModifierPoolEntry entry = pool.get(i);
            int pickedLevel = alreadyPicked.getOrDefault(entry.id(), 0);
            int existingLevel = existingLevels.getOrDefault(entry.id(), 0);
            if (pickedLevel + existingLevel >= entry.maxLevel()) {
                weights[i] = 0;
                continue;
            }
            weights[i] = entry.weight();
            totalWeight += entry.weight();
        }

        if (totalWeight == 0) return null;

        double roll = random.nextDouble() * totalWeight;
        double cumulative = 0;
        for (int i = 0; i < pool.size(); i++) {
            cumulative += weights[i];
            if (roll <= cumulative) return pool.get(i);
        }
        return pool.get(pool.size() - 1); // floating-point safety
    }

    /**
     * Attempts to level up a randomly-chosen existing upgrade or defense modifier.
     * Only considers modifiers below their natural max level, for slot types that still have budget.
     *
     * @return the modifier ID to level up, or null if none eligible
     */
    private static @Nullable ModifierId tryLevelUp(Map<ModifierId, Integer> picked,
            List<ModifierPoolEntry> pool, RandomSource random,
            boolean upgradeSlotAvailable, boolean defenseSlotAvailable,
            Map<ModifierId, Integer> existingLevels) {
        List<ModifierId> candidates = new ArrayList<>();
        for (Map.Entry<ModifierId, Integer> entry : picked.entrySet()) {
            ModifierPoolEntry poolEntry = findEntry(pool, entry.getKey());
            int existingLevel = existingLevels.getOrDefault(entry.getKey(), 0);
            if (poolEntry == null || entry.getValue() + existingLevel >= poolEntry.maxLevel()) continue;
            SlotType st = poolEntry.slotType();
            if ((st == SlotType.UPGRADE && upgradeSlotAvailable)
                    || (st == SlotType.DEFENSE && defenseSlotAvailable)) {
                candidates.add(entry.getKey());
            }
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    private static @Nullable ModifierPoolEntry findEntry(List<ModifierPoolEntry> pool, ModifierId id) {
        for (ModifierPoolEntry entry : pool) {
            if (entry.id().equals(id)) return entry;
        }
        return null;
    }

}
