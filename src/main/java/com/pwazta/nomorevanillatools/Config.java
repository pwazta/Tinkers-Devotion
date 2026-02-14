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

    private static final ForgeConfigSpec.BooleanValue AUTO_GENERATE_RECIPES = BUILDER
            .comment("Auto-generate recipes on first world load.",
                     "Can be disabled for manual control via /nomorevanillatools generate command.",
                     "Delete world/datapacks/nomorevanillatools_generated/.generated to force regeneration.")
            .define("autoGenerateRecipes", true);

    private static final ForgeConfigSpec.BooleanValue REMOVE_VANILLA_TOOL_CRAFTING = BUILDER
            .comment("Remove vanilla tool crafting recipes (prevents crafting wooden/stone/iron/gold/diamond tools).",
                     "Recommended to keep enabled when using this mod.")
            .define("removeVanillaToolCrafting", true);

    private static final ForgeConfigSpec.BooleanValue REMOVE_VANILLA_ARMOR_CRAFTING = BUILDER
            .comment("Remove vanilla armor crafting recipes (leather/iron/gold/diamond armor).",
                     "Recommended when extending TC immersion to armor.")
            .define("removeVanillaArmorCrafting", true);

    private static final ForgeConfigSpec.BooleanValue REQUIRE_OTHER_PARTS_MATCH = BUILDER
            .comment("Require other parts (handle, binding) to also match the tier.",
                     "HEAD MATERIAL ALWAYS MUST MATCH - this controls additional checking.",
                     "When false (default): Only the head material needs to match (recommended by TC creator).",
                     "When true: Other parts must also match based on otherPartsThreshold.")
            .define("requireOtherPartsMatch", false);

    private static final ForgeConfigSpec.DoubleValue OTHER_PARTS_THRESHOLD = BUILDER
            .comment("Percentage of other parts (excluding head) that must match the tier (0.0 to 1.0).",
                     "Only applies if requireOtherPartsMatch is true.",
                     "Example: 0.5 means 50% of non-head parts must match, 1.0 means all must match.")
            .defineInRange("otherPartsThreshold", 0.5, 0.0, 1.0);

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

    private static final ForgeConfigSpec.BooleanValue FORCE_REGENERATE_CONFIG = BUILDER
            .comment("Force regenerate material mappings from TC registry on next server start.",
                     "Uses merge strategy: auto-detected materials are added, existing user entries preserved.",
                     "Useful after installing new mods that add TC materials.",
                     "Merge is idempotent — safe to leave enabled, but costs a small registry scan each boot.")
            .define("forceRegenerateMaterialConfig", false);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    // Config values
    public static boolean autoGenerateRecipes;
    public static boolean removeVanillaToolCrafting;
    public static boolean removeVanillaArmorCrafting;
    public static boolean requireOtherPartsMatch;
    public static double otherPartsThreshold;
    public static boolean replaceLootTableItems;
    public static boolean replaceMobEquipment;
    public static boolean debugLogging;
    public static boolean forceRegenerateMaterialConfig;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        autoGenerateRecipes = AUTO_GENERATE_RECIPES.get();
        removeVanillaToolCrafting = REMOVE_VANILLA_TOOL_CRAFTING.get();
        removeVanillaArmorCrafting = REMOVE_VANILLA_ARMOR_CRAFTING.get();
        requireOtherPartsMatch = REQUIRE_OTHER_PARTS_MATCH.get();
        otherPartsThreshold = OTHER_PARTS_THRESHOLD.get();
        replaceLootTableItems = REPLACE_LOOT_TABLE_ITEMS.get();
        replaceMobEquipment = REPLACE_MOB_EQUIPMENT.get();
        debugLogging = DEBUG_LOGGING.get();
        forceRegenerateMaterialConfig = FORCE_REGENERATE_CONFIG.get();
    }
}
