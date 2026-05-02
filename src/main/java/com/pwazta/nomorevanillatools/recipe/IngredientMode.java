package com.pwazta.nomorevanillatools.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import slimeknights.tconstruct.library.materials.definition.MaterialVariant;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.materials.stats.MaterialStatsId;
import slimeknights.tconstruct.library.tools.definition.ToolDefinition;
import slimeknights.tconstruct.library.tools.definition.module.material.ToolMaterialHook;
import slimeknights.tconstruct.library.tools.helper.ToolBuildHandler;
import slimeknights.tconstruct.library.tools.item.IModifiable;
import slimeknights.tconstruct.library.tools.nbt.MaterialNBT;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * Sealed interface representing a matching mode for {@link TinkerMaterialIngredient}.
 * Each implementation encapsulates the test/display/serialization logic for one category
 * (tools, armor, ranged weapons).
 */
public sealed interface IngredientMode permits ToolMode, ArmorMode, RangedMode, ShieldMode {

    /** Test whether an ItemStack matches this mode's requirements. */
    boolean test(ItemStack stack);

    /** Compute display items for JEI (called by TinkerMaterialIngredient with caching). */
    ItemStack[] computeDisplayItems();

    /** Cache key for display item caching. */
    String displayCacheKey();

    /** Serialize to JSON for recipe datapack generation. */
    JsonElement toJson();

    /** Serialize to network buffer. */
    void write(FriendlyByteBuf buffer);

    /** Mode name string for JSON "mode" field and serializer dispatch. */
    String modeName();

    // ── Static factory methods ───────────────────────────────────────────

    /** Dispatches to the correct mode's fromJson based on the "mode" string field. */
    static IngredientMode fromJson(JsonObject json) {
        String mode = json.get("mode").getAsString().toLowerCase();
        return switch (mode) {
            case "tool_action" -> ToolMode.fromJson(json);
            case "armor_slot"  -> ArmorMode.fromJson(json);
            case "ranged"      -> RangedMode.fromJson(json);
            case "shield"      -> ShieldMode.fromJson(json);
            default -> throw new JsonParseException("Unknown ingredient mode: " + mode);
        };
    }

    /** Dispatches to the correct mode's fromBuffer based on the first UTF string in the buffer. */
    static IngredientMode fromBuffer(FriendlyByteBuf buffer) {
        String mode = buffer.readUtf();
        return switch (mode) {
            case "tool_action" -> ToolMode.fromBuffer(buffer);
            case "armor_slot"  -> ArmorMode.fromBuffer(buffer);
            case "ranged"      -> RangedMode.fromBuffer(buffer);
            case "shield"      -> ShieldMode.fromBuffer(buffer);
            default -> throw new IllegalStateException("Unknown ingredient mode: " + mode);
        };
    }

    // ── Shared display building helper ───────────────────────────────────

    /**
     * Unified display builder for JEI. Each mode supplies a {@code resolver} that decides
     * which materials each part of an item should visually use, and a {@code tagger} that
     * stamps any mode-specific NBT (e.g. {@code nmvt_match_mode}, {@code nmvt_required_tier}).
     *
     * <p>The resolver's return list size carries meaning:
     * <ul>
     *   <li><b>Size == 1 (single material):</b> routed to {@link ToolBuildHandler#createSingleMaterial},
     *       which performs per-slot {@code canUseMaterial} validation and substitutes a sensible default
     *       per slot where the material is incompatible. Use this for items where you want one material
     *       intelligently slotted (e.g. armor plating — applies to plating slot, defaults maille/etc).</li>
     *   <li><b>Size &gt; 1 (per-part list):</b> built via {@link ToolBuildHandler#buildItemFromMaterials}
     *       with the exact material list (no per-slot validation — caller asserts each slot accepts its
     *       material). On empty result, falls back to {@code createSingleMaterial} with the first material,
     *       handling addon items with unexpected part counts.</li>
     * </ul>
     *
     * @param items     eligible TC items to display
     * @param resolver  (item, statTypes) → materials list (1 = smart single-slot, &gt;1 = explicit per-part)
     * @param tagger    (resultStack, statTypes) → applies mode-specific NBT to the built stack
     */
    static ItemStack[] buildMixedDisplayItems(
            List<? extends IModifiable> items,
            BiFunction<IModifiable, List<MaterialStatsId>, List<MaterialVariantId>> resolver,
            BiConsumer<ItemStack, List<MaterialStatsId>> tagger) {
        List<ItemStack> result = new ArrayList<>();
        for (IModifiable item : items) {
            ToolDefinition def = item.getToolDefinition();
            if (!def.isDataLoaded()) continue;
            List<MaterialStatsId> stats = ToolMaterialHook.stats(def);

            List<MaterialVariantId> mats = resolver.apply(item, stats);
            if (mats == null || mats.isEmpty()) continue;

            ItemStack stack;
            if (mats.size() == 1) {
                stack = ToolBuildHandler.createSingleMaterial(item, MaterialVariant.of(mats.get(0)));
            } else {
                MaterialNBT.Builder builder = MaterialNBT.builder();
                for (MaterialVariantId m : mats) builder.add(m);
                stack = ToolBuildHandler.buildItemFromMaterials(item, builder.build());
                if (stack.isEmpty()) {
                    stack = ToolBuildHandler.createSingleMaterial(item, MaterialVariant.of(mats.get(0)));
                }
            }
            if (stack.isEmpty()) continue;

            tagger.accept(stack, stats);
            result.add(stack);
        }
        return result.toArray(new ItemStack[0]);
    }
}
