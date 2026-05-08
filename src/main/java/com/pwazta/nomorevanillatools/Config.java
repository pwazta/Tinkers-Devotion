package com.pwazta.nomorevanillatools;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * Configuration for No More Vanilla Tools mod.
 * Provides options for recipe generation and material checking behavior.
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // === No More Vanilla Tools Config Options ===

    private static final ForgeConfigSpec.BooleanValue DISABLE_VANILLA_CRAFTING = BUILDER
            .comment("Disable vanilla crafting via Mantle's tag-based scan.",
                     "Covers tools, armor, bow, crossbow, shield, fishing rod, flint & steel, shears, brush, netherite smithing.",
                     "Also catches modded recipes producing vanilla items.")
            .define("disableVanillaCrafting", true);

    private static final ForgeConfigSpec.BooleanValue REQUIRE_ALL_PARTS_MATCH = BUILDER
            .comment("Require all parts (including head) to meet a percentage-based match threshold.",
                     "HEAD MATERIAL ALWAYS MUST MATCH - this controls additional checking.",
                     "When false (default): Only the head material needs to match (recommended by TC creator).",
                     "When true: A percentage of ALL parts must match based on allPartsThreshold.")
            .define("requireAllPartsMatch", false);

    private static final ForgeConfigSpec.DoubleValue ALL_PARTS_THRESHOLD = BUILDER
            .comment("Percentage of ALL parts (including head) that must match the tier (0.0 to 1.0).",
                     "Only applies if requireAllPartsMatch is true.",
                     "Example: 0.5 means 50% of all parts must match, 1.0 means every part must match.")
            .defineInRange("allPartsThreshold", 0.5, 0.0, 1.0);

    private static final ForgeConfigSpec.DoubleValue LEVEL_UP_CHANCE = BUILDER
            .comment("When modifiers are added to a tool, this is the chance (0.0 to 1.0) that an",
                     "existing modifier gets leveled up instead of adding a new random one.")
            .defineInRange("levelUpChance", 0.65, 0.0, 1.0);

    private static final ForgeConfigSpec.DoubleValue MODIFIER_WEIGHT_FALLOFF = BUILDER
            .comment("How much specialist modifiers are favored over generalist ones.",
                     "Higher = specialist modifiers (e.g. sharpness on swords) appear more often.",
                     "Lower = all compatible modifiers appear with similar frequency.",
                     "Default 1.5 makes specialists ~8x more likely than universals.")
            .defineInRange("modifierWeightFalloff", 1.5, 0.0, 5.0);

    private static final ForgeConfigSpec.DoubleValue LOOT_VARIANCE_CHANCE = BUILDER
            .comment("Chance per part of selecting a material from any tier (up to maxLootVarianceTier)",
                     "instead of the base tier, in loot/mob replacement for single-tier items.",
                     "Set to 0 to disable. Remaining non-canonical chance stays within base tier.",
                     "At default: 80% canonical, 10% same-tier random, 10% any-tier random.")
            .defineInRange("lootVarianceChance", 0.10, 0.0, 1.0);

    private static final ForgeConfigSpec.IntValue MAX_LOOT_VARIANCE_TIER = BUILDER
            .comment("Hard cap on material tier for loot variance (IMaterial.getTier()).",
                     "0=wood, 1=stone, 2=iron, 3=diamond, 4=netherite.",
                     "Default 3 prevents netherite/dragonsteel materials on random loot.")
            .defineInRange("maxLootVarianceTier", 3, 0, 4);

    private static final ForgeConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Enable debug logging for recipe modifications and ingredient matching.",
                     "Useful for troubleshooting. May spam console.")
            .define("debugLogging", false);

    private static final ForgeConfigSpec.BooleanValue REPLACE_LOOT_TABLE_ITEMS = BUILDER
            .comment("Replace vanilla tools and armor in loot tables (chests, fishing, etc.)",
                     "with Tinkers' Construct equivalents via Global Loot Modifier.")
            .define("replaceLootTableItems", true);

    private static final ForgeConfigSpec.BooleanValue REPLACE_MOB_EQUIPMENT = BUILDER
            .comment("Replace vanilla tools and armor on mobs at spawn time",
                     "with Tinkers' Construct equivalents.",
                     "Mobs will visually hold TC tools and drop them naturally on death.")
            .define("replaceMobEquipment", true);

    private static final ForgeConfigSpec.BooleanValue REPLACE_MOB_RANGED_AI = BUILDER
            .comment("Replace mob AI goals when TC ranged weapons are equipped.",
                     "Required for mobs to actually USE TC bows and crossbows.",
                     "Can be disabled independently from replaceMobEquipment for debugging.")
            .define("replaceMobRangedAI", true);

    private static final ForgeConfigSpec.BooleanValue REPLACE_VILLAGER_TRADES = BUILDER
            .comment("Replace vanilla tools/armor/ranged/shields/fishing-rods/flint-and-steel",
                     "in villager and wandering-trader trade outputs with TC equivalents.",
                     "The rolled trade is persisted on the villager, so each villager keeps the",
                     "same trade across save/load — different villagers will roll different ones.",
                     "Trade INPUTS are intentionally left untouched (see CONTEXT.md).")
            .define("replaceVillagerTrades", true);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    // Config values
    public static boolean disableVanillaCrafting;
    public static boolean requireAllPartsMatch;
    public static double allPartsThreshold;
    public static boolean replaceLootTableItems;
    public static boolean replaceMobEquipment;
    public static boolean replaceMobRangedAI;
    public static boolean replaceVillagerTrades;
    public static double levelUpChance;
    public static double modifierWeightFalloff;
    public static double lootVarianceChance;
    public static int maxLootVarianceTier;
    public static boolean debugLogging;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        disableVanillaCrafting = DISABLE_VANILLA_CRAFTING.get();
        requireAllPartsMatch = REQUIRE_ALL_PARTS_MATCH.get();
        allPartsThreshold = ALL_PARTS_THRESHOLD.get();
        replaceLootTableItems = REPLACE_LOOT_TABLE_ITEMS.get();
        replaceMobEquipment = REPLACE_MOB_EQUIPMENT.get();
        replaceMobRangedAI = REPLACE_MOB_RANGED_AI.get();
        replaceVillagerTrades = REPLACE_VILLAGER_TRADES.get();
        levelUpChance = LEVEL_UP_CHANCE.get();
        modifierWeightFalloff = MODIFIER_WEIGHT_FALLOFF.get();
        lootVarianceChance = LOOT_VARIANCE_CHANCE.get();
        maxLootVarianceTier = MAX_LOOT_VARIANCE_TIER.get();
        debugLogging = DEBUG_LOGGING.get();
    }
}
