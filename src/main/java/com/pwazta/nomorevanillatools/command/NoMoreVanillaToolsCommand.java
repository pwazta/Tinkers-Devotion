package com.pwazta.nomorevanillatools.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.pwazta.nomorevanillatools.ExampleMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Registers the /nomorevanillatools command.
 * This command provides utilities for recipe generation and management.
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class NoMoreVanillaToolsCommand {

    /**
     * Registers commands when the server starts.
     *
     * @param event The RegisterCommandsEvent
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // Build the main command
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("nomorevanillatools")
            .requires(source -> source.hasPermission(2));  // Requires OP level 2

        // Add generate subcommand
        command.then(Commands.literal("generate")
            .executes(GenerateRecipesCommand::run));

        // Register the command
        dispatcher.register(command);
    }
}
