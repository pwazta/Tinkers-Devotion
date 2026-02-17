package com.pwazta.nomorevanillatools.recipe;

import com.pwazta.nomorevanillatools.ExampleMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.common.crafting.IIngredientSerializer;

/** Registers custom ingredient serializers. Called from commonSetup via enqueueWork. */
public class ModRecipeSerializers {

    public static IIngredientSerializer<TinkerMaterialIngredient> TINKER_MATERIAL_INGREDIENT;

    public static void register() {
        TINKER_MATERIAL_INGREDIENT = CraftingHelper.register(
                new ResourceLocation(ExampleMod.MODID, "tinker_material"),
                TinkerMaterialIngredient.Serializer.INSTANCE
        );
    }

    public static IIngredientSerializer<TinkerMaterialIngredient> get() {
        if (TINKER_MATERIAL_INGREDIENT == null) {
            throw new IllegalStateException("Ingredient serializers not yet registered!");
        }
        return TINKER_MATERIAL_INGREDIENT;
    }
}
