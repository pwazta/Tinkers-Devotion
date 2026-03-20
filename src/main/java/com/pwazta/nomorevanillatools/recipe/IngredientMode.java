package com.pwazta.nomorevanillatools.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import slimeknights.tconstruct.library.materials.definition.MaterialVariant;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.tools.helper.ToolBuildHandler;
import slimeknights.tconstruct.library.tools.item.IModifiable;

import java.util.ArrayList;
import java.util.List;

/**
 * Sealed interface representing a matching mode for {@link TinkerMaterialIngredient}.
 * Each implementation encapsulates the test/display/serialization logic for one category
 * (tools, armor, ranged weapons).
 */
public sealed interface IngredientMode permits ToolMode, ArmorMode, RangedMode {

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
            default -> throw new IllegalStateException("Unknown ingredient mode: " + mode);
        };
    }

    // ── Shared display building helper ───────────────────────────────────

    /**
     * Builds display ItemStacks for JEI from a list of valid items and a canonical material.
     * Canonical material is resolved by MaterialMappingConfig (shared with loot generation).
     */
    static ItemStack[] buildDisplayItems(@Nullable String canonicalId, String tierLabel, List<? extends IModifiable> items) {
        if (canonicalId == null) return new ItemStack[0];

        MaterialVariantId variantId = MaterialVariantId.tryParse(canonicalId);
        if (variantId == null) return new ItemStack[0];
        MaterialVariant displayVariant = MaterialVariant.of(variantId);

        List<ItemStack> displayItems = new ArrayList<>();
        for (IModifiable item : items) {
            ItemStack displayStack = ToolBuildHandler.createSingleMaterial(item, displayVariant);
            if (!displayStack.isEmpty()) {
                displayStack.getOrCreateTag().putString("nmvt_required_tier", tierLabel);
                displayItems.add(displayStack);
            }
        }
        return displayItems.toArray(new ItemStack[0]);
    }
}
