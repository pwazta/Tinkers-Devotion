package com.pwazta.tinkersdevotion;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = TinkersDevotion.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue DISABLE_VANILLA_CRAFTING = BUILDER
            .comment("Disable crafting (via Mantle) of vanilla tools, armor, ranged weapons, shields, fishing rods, flint & steel, shears, brush, and netherite smithing.",
                     "Catches modded recipes producing those items too.")
            .define("disableVanillaCrafting", true);

    private static final ForgeConfigSpec.DoubleValue PARTS_MATCH_THRESHOLD = BUILDER
            .comment("Required match ratio for tool part materials (head ALWAYS must match the tier).",
                     "0.0 = head only (default). 0.5 = at least 50% of parts must match. 1.0 = every part must match.")
            .defineInRange("partsMatchThreshold", 0.0, 0.0, 1.0);

    private static final ForgeConfigSpec.DoubleValue LEVEL_UP_CHANCE = BUILDER
            .comment("Chance (0.0-1.0) that a randomly added modifier levels up an existing one instead of adding a new one.")
            .defineInRange("levelUpChance", 0.65, 0.0, 1.0);

    private static final ForgeConfigSpec.DoubleValue MODIFIER_WEIGHT_FALLOFF = BUILDER
            .comment("How strongly specialist modifiers (e.g. sharpness on swords) are favored over generalists.",
                     "Default 1.5 makes specialists ~8x more likely than universals. Higher = more specialist.")
            .defineInRange("modifierWeightFalloff", 1.5, 0.0, 5.0);

    private static final ForgeConfigSpec.DoubleValue LOOT_VARIANCE_CHANCE = BUILDER
            .comment("Per-part chance of picking a material from any tier (capped by maxLootVarianceTier).",
                     "Default 0.10 gives roughly 80% canonical / 10% same-tier random / 10% any-tier random.")
            .defineInRange("lootVarianceChance", 0.10, 0.0, 1.0);

    private static final ForgeConfigSpec.IntValue MAX_LOOT_VARIANCE_TIER = BUILDER
            .comment("Hard cap on material tier for single-tier loot variance.",
                     "0=wood, 1=stone, 2=iron, 3=diamond, 4=netherite. Default 3 prevents netherite-tier on random loot.")
            .defineInRange("maxLootVarianceTier", 3, 0, 4);

    private static final ForgeConfigSpec.BooleanValue REPLACE_LOOT_TABLE_ITEMS = BUILDER
            .comment("Replace vanilla tools/armor/ranged in chests, fishing, and other loot tables with TC equivalents.")
            .define("replaceLootTableItems", true);

    private static final ForgeConfigSpec.BooleanValue REPLACE_MOB_EQUIPMENT = BUILDER
            .comment("Replace vanilla tools/armor on mob spawn equipment with TC equivalents.",
                     "Mobs hold TC tools and drop them naturally on death.")
            .define("replaceMobEquipment", true);

    private static final ForgeConfigSpec.BooleanValue REPLACE_MOB_RANGED_AI = BUILDER
            .comment("Required for mobs to actually use TC bows and crossbows.",
                     "Toggle independently from replaceMobEquipment for debugging.")
            .define("replaceMobRangedAI", true);

    private static final ForgeConfigSpec.BooleanValue REPLACE_VILLAGER_TRADES = BUILDER
            .comment("Replace vanilla tools/armor/ranged/shields/fishing rods/flint and steel in villager and wandering-trader trade outputs.",
                     "Each villager keeps its rolled trade across save/load. Trade INPUTS are not replaced.")
            .define("replaceVillagerTrades", true);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static boolean disableVanillaCrafting;
    public static double partsMatchThreshold;
    public static boolean replaceLootTableItems;
    public static boolean replaceMobEquipment;
    public static boolean replaceMobRangedAI;
    public static boolean replaceVillagerTrades;
    public static double levelUpChance;
    public static double modifierWeightFalloff;
    public static double lootVarianceChance;
    public static int maxLootVarianceTier;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        disableVanillaCrafting = DISABLE_VANILLA_CRAFTING.get();
        partsMatchThreshold = PARTS_MATCH_THRESHOLD.get();
        replaceLootTableItems = REPLACE_LOOT_TABLE_ITEMS.get();
        replaceMobEquipment = REPLACE_MOB_EQUIPMENT.get();
        replaceMobRangedAI = REPLACE_MOB_RANGED_AI.get();
        replaceVillagerTrades = REPLACE_VILLAGER_TRADES.get();
        levelUpChance = LEVEL_UP_CHANCE.get();
        modifierWeightFalloff = MODIFIER_WEIGHT_FALLOFF.get();
        lootVarianceChance = LOOT_VARIANCE_CHANCE.get();
        maxLootVarianceTier = MAX_LOOT_VARIANCE_TIER.get();
    }
}
