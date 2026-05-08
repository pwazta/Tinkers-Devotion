package com.pwazta.tinkersdevotion.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.pwazta.tinkersdevotion.TinkersDevotion;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Registers the /tinkersdevotion command tree. */
@Mod.EventBusSubscriber(modid = TinkersDevotion.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class TinkersDevotionCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("tinkersdevotion")
            .requires(source -> source.hasPermission(2));

        command.then(Commands.literal("generate")
            .executes(GenerateRecipesCommand::run)
            .then(Commands.literal("reset")
                .executes(GenerateRecipesCommand::runReset)));

        dispatcher.register(command);
    }
}
