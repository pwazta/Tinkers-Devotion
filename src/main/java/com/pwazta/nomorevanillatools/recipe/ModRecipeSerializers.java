package com.pwazta.nomorevanillatools.recipe;

import com.pwazta.nomorevanillatools.ExampleMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.common.crafting.IIngredientSerializer;

/**
 * Registry for custom recipe serializers including the TinkerMaterialIngredient serializer.
 */
public class ModRecipeSerializers {

    // Registered ingredient serializer instance
    public static IIngredientSerializer<TinkerMaterialIngredient> TINKER_MATERIAL_INGREDIENT;

    /**
     * Registers all custom ingredient serializers.
     * Should be called during FMLCommonSetupEvent.
     */
    public static void register() {
        TINKER_MATERIAL_INGREDIENT = CraftingHelper.register(
                new ResourceLocation(ExampleMod.MODID, "tinker_material"),
                TinkerMaterialIngredient.Serializer.INSTANCE
        );
    }

    /**
     * Gets the registered TinkerMaterialIngredient serializer.
     *
     * @return The serializer instance
     */
    public static IIngredientSerializer<TinkerMaterialIngredient> get() {
        if (TINKER_MATERIAL_INGREDIENT == null) {
            throw new IllegalStateException("Ingredient serializers not yet registered!");
        }
        return TINKER_MATERIAL_INGREDIENT;
    }
}
