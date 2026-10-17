package com.kinetic.client.command;

import com.kinetic.Kinetic;
import com.kinetic.capability.PlayerAnimatableCapabilityProvider;
import com.kinetic.client.animation.molang.CustomMolangParser;
import com.kinetic.client.command.sub.DebugCommand;
import com.kinetic.client.command.sub.MolangCommand;
import com.kinetic.client.command.sub.SimpleWatchCommand;
import com.kinetic.client.entity.CustomEntity;
import com.kinetic.client.entity.CustomPlayerEntity;
import com.kinetic.client.entity.IRoamingEntity;
import com.kinetic.client.gui.overlay.DebugAnimationScreen;
import com.kinetic.geckolib3.core.controller.IAnimationController;
import com.kinetic.geckolib3.core.molang.binding.ContextBinding;
import com.kinetic.client.animation.molang.roaming.LocalRoamingStruct;
import com.kinetic.geckolib3.core.molang.util.StringPool;
import com.kinetic.util.CommandUtil;
import com.google.common.collect.Sets;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@SuppressWarnings("removal")
public class ClientRootCommand {
    private static final String ROOT_NAME = "kineticclient";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT_NAME)
                .requires(source -> CommandUtil.isLocalPlayer(source.getEntity()));
        root.then(MolangCommand.get());
        root.then(SimpleWatchCommand.get());
        root.then(DebugCommand.get());
        dispatcher.register(root);
    }

    public static final SuggestionProvider<CommandSourceStack> ALL_VARS = SuggestionProviders.register(new ResourceLocation(Kinetic.MOD_ID, "vars"), (source, builder) -> {
        if (source.getSource() instanceof SharedSuggestionProvider && FMLEnvironment.dist == Dist.CLIENT) {
            return getTarget().map(cap -> {
                // v 变量
                Set<String> vars = Sets.newHashSet();
                cap.getAnimationProcessor().visitScopedVariableNames(name -> {
                    vars.add(String.format("v.%s", name));
                });

                // v.roaming 变量
                if (cap instanceof IRoamingEntity roaming && roaming.getRoamingStruct() instanceof LocalRoamingStruct struct) {
                    struct.visitNames(s -> {
                        Object object = struct.getProperty(StringPool.getName(s));
                        if (object != null) {
                            vars.add(String.format("v.roaming.%s", s));
                        }
                    });
                }

                // 其他上下文变量
                CustomMolangParser.getAllBinding().forEach((key, value) -> {
                    if (value instanceof ContextBinding ctx) {
                        ctx.getAllName().forEach(s -> vars.add(String.format("%s.%s", key, s)));
                    }
                });

                // 自定义函数
                for (var name : cap.getModelRenderTarget().assets().userFunctions().keySet()) {
                    vars.add(String.format("fn.%s", name));
                }

                return SharedSuggestionProvider.suggest(vars, builder);
            }).orElseGet(Suggestions::empty);
        }
        return Suggestions.empty();
    });

    public static final SuggestionProvider<CommandSourceStack> ALL_CONTROLLERS = SuggestionProviders.register(new ResourceLocation(Kinetic.MOD_ID, "controllers"), (source, builder) -> {
        if (source.getSource() instanceof SharedSuggestionProvider && FMLEnvironment.dist == Dist.CLIENT) {
            return getTarget().map(target -> {
                Set<String> vars = target.getAnimationData()
                        .getAnimationControllers()
                        .stream()
                        .map(IAnimationController::getName)
                        .collect(Collectors.toSet());
                return SharedSuggestionProvider.suggest(vars, builder);
            }).orElseGet(Suggestions::empty);
        }
        return Suggestions.empty();
    });

    private static Optional<CustomEntity<?>> getTarget() {
        var target = DebugAnimationScreen.getTarget();
        if (target == null) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                target = player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
            }
        }
        return Optional.ofNullable(target);
    }
}
