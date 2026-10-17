package com.kinetic.command;

import com.kinetic.Kinetic;
import com.kinetic.command.sub.*;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class RootCommand {
    private static final String ROOT_NAME = "kinetic";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT_NAME);
        root.then(ModelCommand.get());
        root.then(AuthCommand.get());
        root.then(ExportCommand.get());
        root.then(PlayAnimationCommand.get());
        root.then(MolangCommand.get());
        root.then(PingCommand.get());
        dispatcher.register(root);
    }

    public static void registerPlaceholder(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT_NAME);
        root.then(Commands.argument("any", StringArgumentType.greedyString()).executes(ctx -> {
            if (ctx.getSource().isPlayer()) {
                ctx.getSource().sendSystemMessage(Kinetic.getUnavailableMessage());
            } else {
                ctx.getSource().sendSystemMessage(Component.literal(Kinetic.getUnavailableMessageString()));
            }
            return Command.SINGLE_SUCCESS;
        }));
        dispatcher.register(root);
    }
}
