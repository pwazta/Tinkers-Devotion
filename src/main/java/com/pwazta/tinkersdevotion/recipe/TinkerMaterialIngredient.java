package com.pwazta.tinkersdevotion.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.crafting.AbstractIngredient;
import net.minecraftforge.common.crafting.IIngredientSerializer;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Custom ingredient that matches Tinker's Construct tools, armor, or ranged weapons by material tier and type.
 * Delegates all mode-specific logic to {@link IngredientMode} implementations.
 */
public class TinkerMaterialIngredient extends AbstractIngredient {

    // ── Caches ──────────────────────────────────────────────────────────

    /** Cache of display ItemStacks per type:tier key. Built from TcItemRegistry + canonical material. */
    private static final Map<String, ItemStack[]> DISPLAY_CACHE = new ConcurrentHashMap<>();

    /** Clears display cache. Called when exclusions or material mappings change. */
    public static void clearDisplayCache() {
        DISPLAY_CACHE.clear();
    }

    // ── Instance fields ──────────────────────────────────────────────────

    private final IngredientMode mode;

    public TinkerMaterialIngredient(IngredientMode mode) {
        super(Stream.empty());
        this.mode = mode;
    }

    // ── test() — main matching logic ─────────────────────────────────────

    @Override
    public boolean test(@Nullable ItemStack stack) {
        return stack != null && !stack.isEmpty() && mode.test(stack);
    }

    // ── Display items (JEI) ──────────────────────────────────────────────

    @Override
    public ItemStack[] getItems() {
        return DISPLAY_CACHE.computeIfAbsent(mode.displayCacheKey(), k -> mode.computeDisplayItems());
    }

    // ── AbstractIngredient overrides ─────────────────────────────────────

    @Override public boolean isSimple() { return false; }
    @Override public boolean isEmpty() { return false; }

    @Override
    public IIngredientSerializer<? extends TinkerMaterialIngredient> getSerializer() {
        return ModRecipeSerializers.get();
    }

    @Override
    public JsonElement toJson() {
        return mode.toJson();
    }

    public IngredientMode getMode() {
        return mode;
    }

    // ── Serializer ───────────────────────────────────────────────────────

    public static class Serializer implements IIngredientSerializer<TinkerMaterialIngredient> {

        public static final Serializer INSTANCE = new Serializer();

        @Override
        public TinkerMaterialIngredient parse(JsonObject json) {
            return new TinkerMaterialIngredient(IngredientMode.fromJson(json));
        }

        @Override
        public TinkerMaterialIngredient parse(FriendlyByteBuf buffer) {
            return new TinkerMaterialIngredient(IngredientMode.fromBuffer(buffer));
        }

        @Override
        public void write(FriendlyByteBuf buffer, TinkerMaterialIngredient ingredient) {
            buffer.writeUtf(ingredient.getMode().modeName());
            ingredient.getMode().write(buffer);
        }
    }
}
