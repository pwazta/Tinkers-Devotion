package com.pwazta.tinkersdevotion.loot;

import com.mojang.serialization.Codec;
import com.pwazta.tinkersdevotion.TinkersDevotion;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registers Global Loot Modifier codecs for the mod.
 * DeferredRegister must be added to the mod event bus in TinkersDevotion.
 */
public class ModGlobalLootModifiers {

    public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> GLM =
        DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, TinkersDevotion.MODID);

    public static final RegistryObject<Codec<VanillaLootReplacer>> REPLACE_VANILLA_LOOT =
        GLM.register("replace_vanilla_loot", () -> VanillaLootReplacer.CODEC);
}
