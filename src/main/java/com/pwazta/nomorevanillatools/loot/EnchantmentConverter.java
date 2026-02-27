package com.pwazta.nomorevanillatools.loot;

import com.mojang.logging.LogUtils;
import com.pwazta.nomorevanillatools.config.ModifierSkipListConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import slimeknights.tconstruct.library.modifiers.ModifierEntry;
import slimeknights.tconstruct.library.modifiers.ModifierId;
import slimeknights.tconstruct.library.recipe.TinkerRecipeTypes;
import slimeknights.tconstruct.library.recipe.modifiers.adding.AbstractModifierRecipe;
import slimeknights.tconstruct.library.tools.SlotType;
import slimeknights.tconstruct.library.tools.nbt.ModifierNBT;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Converts vanilla enchantment "power budget" into randomized TC modifiers.
 *
 * Called from {@link TinkerToolBuilder#buildFromMaterials} when replacing vanilla
 * loot/mob equipment items with TC equivalents. The conversion uses a three-layer
 * weighted selection algorithm:
 * <ol>
 *   <li>Compatibility filter — only modifiers whose recipe accepts the tool type</li>
 *   <li>Category weighting — primary/secondary/misc weights per tool category</li>
 *   <li>Level-up preference — 35% chance to level up an existing modifier</li>
 * </ol>
 *
 * Modifier pool is lazily built from TC tinker station recipes on first use, cached
 * for the server lifecycle, and cleared on materials reload or config changes.
 */
public class EnchantmentConverter {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ── Budget constants ────────────────────────────────────────────────

    /** Enchant levels → budget scaling factor. */
    private static final double POWER_MULTIPLIER = 3.5;
    /** Maximum modifier budget (caps total modifiers applied). */
    private static final int MAX_BUDGET = 7;
    /** Maximum bonus slot modifiers (writable, harmonious, recapitated, forecast). */
    private static final int MAX_BONUS = 4;
    /** Maximum level for any single randomly-applied modifier. */
    private static final int INDIVIDUAL_LEVEL_CAP = 3;
    /** Probability of leveling up an existing upgrade modifier instead of adding new. */
    private static final float LEVEL_UP_CHANCE = 0.35f;
    /** Minimum budget required before an ability-slot modifier can be picked. */
    private static final int ABILITY_BUDGET_THRESHOLD = 4;

    // ── Weight constants ────────────────────────────────────────────────

    private static final double PRIMARY_WEIGHT = 0.60;
    private static final double SECONDARY_WEIGHT = 0.30;
    private static final double MISC_WEIGHT = 0.10;

    // ── Bonus slot modifiers (applied as slot expansion, excluded from random pool) ──

    private static final List<ModifierId> BONUS_SLOT_ORDER = List.of(
        new ModifierId("tconstruct", "writable"),
        new ModifierId("tconstruct", "harmonious"),
        new ModifierId("tconstruct", "recapitated"),
        new ModifierId("tconstruct", "forecast")
    );

    private static final Set<ModifierId> BONUS_MODIFIERS = Set.copyOf(BONUS_SLOT_ORDER);

    // ── Item tags for tool category detection ───────────────────────────

    private static final TagKey<Item> MELEE_TAG = TagKey.create(Registries.ITEM,
        new ResourceLocation("tconstruct", "modifiable/melee"));
    private static final TagKey<Item> HARVEST_TAG = TagKey.create(Registries.ITEM,
        new ResourceLocation("tconstruct", "modifiable/harvest"));
    private static final TagKey<Item> ARMOR_TAG = TagKey.create(Registries.ITEM,
        new ResourceLocation("tconstruct", "modifiable/armor"));
    private static final TagKey<Item> RANGED_TAG = TagKey.create(Registries.ITEM,
        new ResourceLocation("tconstruct", "modifiable/ranged"));

    // ── Category weight maps (category → modifierId → tier) ─────────────

    private static final Map<String, Map<ModifierId, String>> CATEGORY_WEIGHTS = buildCategoryWeights();

    // ── Pool cache (volatile + double-checked locking) ──────────────────

    private static volatile Map<ModifierId, ModifierPoolEntry> poolCache;

    // ── Reflection for AbstractModifierRecipe.toolRequirement ────────────

    private static Field toolRequirementField;
    private static boolean reflectionFailed;

    // ── Inner record ────────────────────────────────────────────────────

    /** Cached info about a modifier available from TC recipes. */
    record ModifierPoolEntry(ModifierId id, SlotType slotType, int maxLevel, @Nullable Ingredient toolRequirement) {}

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
        int bonusNeeded = Math.max(0, Math.min(budget - baseFreeUpgrades, MAX_BONUS));
        int freeUpgrades = baseFreeUpgrades + bonusNeeded;

        // 4. Get compatible modifier pool (cached globally, filtered per tool).
        //    createStack() here is lightweight — item identity is all that matters for tag matching.
        ItemStack representative = toolStack.createStack();
        List<ModifierPoolEntry> pool = getCompatiblePool(representative);
        if (pool.isEmpty()) return;

        // 5. Detect tool category for weighting
        String category = detectCategory(representative);

        // 6. Pick modifiers from weighted pool
        Map<ModifierId, Integer> picked = new LinkedHashMap<>();
        int upgradesUsed = 0;
        boolean abilityPicked = false;
        int defenseUsed = 0;

        for (int i = 0; i < budget; i++) {
            // Layer 3: 35% chance to level up an existing upgrade modifier
            if (!picked.isEmpty() && upgradesUsed < freeUpgrades && random.nextFloat() < LEVEL_UP_CHANCE) {
                ModifierId leveled = tryLevelUp(picked, pool, random);
                if (leveled != null) {
                    picked.merge(leveled, 1, Integer::sum);
                    upgradesUsed++;
                    continue;
                }
            }

            // Pick a new modifier from weighted pool
            ModifierPoolEntry entry = weightedPick(pool, category, picked, random);
            if (entry == null) continue;

            if (entry.slotType() == SlotType.ABILITY) {
                if (!abilityPicked && budget >= ABILITY_BUDGET_THRESHOLD && freeAbilities > 0) {
                    picked.put(entry.id(), 1);
                    abilityPicked = true;
                }
            } else if (entry.slotType() == SlotType.DEFENSE) {
                if (defenseUsed < freeDefense) {
                    picked.merge(entry.id(), 1, Integer::sum);
                    defenseUsed++;
                }
            } else { // UPGRADE
                if (upgradesUsed < freeUpgrades) {
                    picked.merge(entry.id(), 1, Integer::sum);
                    upgradesUsed++;
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
     * Formula: min(ceil(sqrt(totalLevels * 3.5)), 7)
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
     * Scans all TC tinker station recipes for modifier recipes and builds the pool.
     * Extracts modifier ID, slot type, max level, and tool requirement from each recipe.
     * Deduplicates by modifier ID (keeps highest max level) and filters out skip-list entries.
     */
    private static Map<ModifierId, ModifierPoolEntry> buildPool() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            LOGGER.debug("Server not available — modifier pool not built");
            return Map.of();
        }

        var recipeManager = server.getRecipeManager();
        Set<ModifierId> skipList = ModifierSkipListConfig.getSkipList();
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

            // Get tool requirement via reflection (null = compatible with all tools)
            Ingredient toolReq = getToolRequirement(modRecipe);

            // Deduplicate: keep highest maxLevel if multiple recipes for the same modifier
            pool.merge(modId, new ModifierPoolEntry(modId, slotType, maxLevel, toolReq),
                (existing, incoming) -> existing.maxLevel() >= incoming.maxLevel() ? existing : incoming);
        }

        LOGGER.info("Built modifier pool with {} entries from TC recipes", pool.size());
        return Collections.unmodifiableMap(pool);
    }

    /** Filters the global pool to modifiers compatible with the given tool. */
    private static List<ModifierPoolEntry> getCompatiblePool(ItemStack representative) {
        Map<ModifierId, ModifierPoolEntry> pool = ensurePool();
        if (pool.isEmpty()) return List.of();

        List<ModifierPoolEntry> compatible = new ArrayList<>();
        for (ModifierPoolEntry entry : pool.values()) {
            SlotType st = entry.slotType();
            if (st != SlotType.UPGRADE && st != SlotType.ABILITY && st != SlotType.DEFENSE) continue;
            if (entry.toolRequirement() != null && !entry.toolRequirement().test(representative)) continue;
            compatible.add(entry);
        }
        return compatible;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Category detection
    // ═══════════════════════════════════════════════════════════════════

    /** Determines the tool category from item tags. Falls back to "melee". */
    private static String detectCategory(ItemStack representative) {
        if (representative.is(MELEE_TAG)) return "melee";
        if (representative.is(HARVEST_TAG)) return "mining";
        if (representative.is(ARMOR_TAG)) return "armor";
        if (representative.is(RANGED_TAG)) return "ranged";
        return "melee";
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Category weight maps
    // ═══════════════════════════════════════════════════════════════════

    private static Map<String, Map<ModifierId, String>> buildCategoryWeights() {
        Map<String, Map<ModifierId, String>> weights = new HashMap<>();

        Map<ModifierId, String> melee = new HashMap<>();
        addWeights(melee, "primary", "sharpness", "smite", "bane_of_sssss", "fiery", "severing", "necrotic");
        addWeights(melee, "secondary", "knockback", "luck", "sweeping_edge", "experienced");
        addWeights(melee, "misc", "reinforced", "soulbound");
        weights.put("melee", Map.copyOf(melee));

        Map<ModifierId, String> mining = new HashMap<>();
        addWeights(mining, "primary", "haste", "fortune", "experienced");
        addWeights(mining, "secondary", "sharpness", "reinforced");
        addWeights(mining, "misc", "knockback", "fiery");
        weights.put("mining", Map.copyOf(mining));

        Map<ModifierId, String> armor = new HashMap<>();
        addWeights(armor, "primary", "protection", "fire_protection", "blast_protection",
            "projectile_protection", "thorns");
        addWeights(armor, "secondary", "reinforced", "respiration");
        addWeights(armor, "misc", "aqua_affinity", "soulbound");
        weights.put("armor", Map.copyOf(armor));

        Map<ModifierId, String> ranged = new HashMap<>();
        addWeights(ranged, "primary", "power", "punch", "quick_charge");
        addWeights(ranged, "secondary", "fiery", "piercing");
        addWeights(ranged, "misc", "reinforced", "experienced");
        weights.put("ranged", Map.copyOf(ranged));

        return Map.copyOf(weights);
    }

    private static void addWeights(Map<ModifierId, String> map, String tier, String... modifierNames) {
        for (String name : modifierNames) {
            map.put(new ModifierId("tconstruct", name), tier);
        }
    }

    /** Returns the weight for a modifier in the given category. Unknown modifiers default to misc. */
    private static double getCategoryWeight(ModifierId modId, String category) {
        Map<ModifierId, String> categoryMap = CATEGORY_WEIGHTS.get(category);
        String tier = categoryMap != null ? categoryMap.getOrDefault(modId, "misc") : "misc";
        return switch (tier) {
            case "primary" -> PRIMARY_WEIGHT;
            case "secondary" -> SECONDARY_WEIGHT;
            default -> MISC_WEIGHT;
        };
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Selection algorithms
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Weighted random selection from the compatible pool.
     * Excludes modifiers already at their level cap. Uses category weights.
     */
    private static @Nullable ModifierPoolEntry weightedPick(List<ModifierPoolEntry> pool, String category,
            Map<ModifierId, Integer> alreadyPicked, RandomSource random) {
        double totalWeight = 0;
        double[] weights = new double[pool.size()];

        for (int i = 0; i < pool.size(); i++) {
            ModifierPoolEntry entry = pool.get(i);
            int currentLevel = alreadyPicked.getOrDefault(entry.id(), 0);
            if (currentLevel >= Math.min(entry.maxLevel(), INDIVIDUAL_LEVEL_CAP)) {
                weights[i] = 0;
                continue;
            }
            double weight = getCategoryWeight(entry.id(), category);
            weights[i] = weight;
            totalWeight += weight;
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
     * Attempts to level up a randomly-chosen existing upgrade modifier.
     * Only considers upgrade-slot modifiers below their level cap.
     *
     * @return the modifier ID to level up, or null if none eligible
     */
    private static @Nullable ModifierId tryLevelUp(Map<ModifierId, Integer> picked,
            List<ModifierPoolEntry> pool, RandomSource random) {
        List<ModifierId> candidates = new ArrayList<>();
        for (Map.Entry<ModifierId, Integer> entry : picked.entrySet()) {
            ModifierPoolEntry poolEntry = findEntry(pool, entry.getKey());
            if (poolEntry != null
                    && poolEntry.slotType() == SlotType.UPGRADE
                    && entry.getValue() < Math.min(poolEntry.maxLevel(), INDIVIDUAL_LEVEL_CAP)) {
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

    /**
     * Extracts the toolRequirement field from AbstractModifierRecipe via reflection.
     * One-time cost per field lookup; returns null if reflection fails (modifier treated
     * as compatible with all tools).
     */
    private static @Nullable Ingredient getToolRequirement(AbstractModifierRecipe recipe) {
        if (reflectionFailed) return null;
        try {
            if (toolRequirementField == null) {
                toolRequirementField = AbstractModifierRecipe.class.getDeclaredField("toolRequirement");
                toolRequirementField.setAccessible(true);
            }
            return (Ingredient) toolRequirementField.get(recipe);
        } catch (NoSuchFieldException e) {
            LOGGER.warn("AbstractModifierRecipe.toolRequirement field not found — "
                + "modifier pool will not filter by tool compatibility");
            reflectionFailed = true;
            return null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
