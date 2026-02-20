package net.rain.rainjava.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.rain.rainjava.RainJava;
import net.rain.rainjava.core.ScriptType;
import net.rain.rainjava.logging.RainJavaLogger;
import net.rain.rainjava.logging.ScriptErrorCollector;

/**
 * RainJava 指令树，注册 /java 和 /j（别名）两组指令。
 *
 * 修改记录：
 *  - reload 出现错误时，聊天栏增加可点击的 [View Error Screen] 链接（执行客户端命令）
 *  - 将客户端类 RainJavaClientEvents 的调用全部包裹在 DistExecutor.unsafeRunWhenOn(CLIENT)，
 *    避免在专用服务器上引用客户端类导致 NoClassDefFoundError
 */
@Mod.EventBusSubscriber(modid = RainJava.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RainJavaCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(buildRoot("java"));
        d.register(buildRoot("j"));
        RainJava.LOGGER.info("RainJava commands registered (/java, /j)");
    }

    // -----------------------------------------------------------------------
    // 指令树构建
    // -----------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> buildRoot(String name) {
        return Commands.literal(name)
                .requires(s -> s.hasPermission(2))

                .then(Commands.literal("reload")
                        .then(Commands.literal("startup")
                                .executes(ctx -> reload(ctx, ScriptType.STARTUP)))
                        .then(Commands.literal("server")
                                .executes(ctx -> reload(ctx, ScriptType.SERVER)))
                        .then(Commands.literal("client")
                                .executes(ctx -> reload(ctx, ScriptType.CLIENT)))
                        .executes(RainJavaCommands::reloadAll))

                .then(Commands.literal("hand")
                        .then(Commands.literal("getId")
                                .executes(RainJavaCommands::handGetId))
                        .then(Commands.literal("getClass")
                                .executes(RainJavaCommands::handGetClass)))

                .then(Commands.literal("errors")
                        .then(Commands.literal("startup")
                                .executes(ctx -> showErrors(ctx, ScriptType.STARTUP)))
                        .then(Commands.literal("server")
                                .executes(ctx -> showErrors(ctx, ScriptType.SERVER)))
                        .then(Commands.literal("client")
                                .executes(ctx -> showErrors(ctx, ScriptType.CLIENT)))
                        .executes(RainJavaCommands::showAllErrors));
    }

    // -----------------------------------------------------------------------
    // reload 指令实现
    // -----------------------------------------------------------------------

    private static int reload(CommandContext<CommandSourceStack> ctx, ScriptType type) {
        CommandSourceStack src = ctx.getSource();
        feedback(src, ChatFormatting.YELLOW,
                "▶ RainJava: Reloading " + type.getName() + " scripts...");

        try {
            ScriptErrorCollector.clear(type);

            // 使用 DistExecutor 安全调用客户端类，避免在专用服务器崩溃
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> net.rain.rainjava.client.RainJavaClientEvents.resetShownFlag());

            RainJava.getCore().reload(type);

            int errCount  = ScriptErrorCollector.getErrors(type).size();
            int warnCount = ScriptErrorCollector.getWarnings(type).size();

            if (errCount == 0 && warnCount == 0) {
                feedback(src, ChatFormatting.GREEN,
                        "✔ RainJava: " + type.getName() + " scripts reloaded successfully.");
            } else {
                feedback(src, ChatFormatting.RED,
                        "✘ RainJava: " + type.getName() + " reload finished with "
                                + errCount + " error(s) and " + warnCount + " warning(s).");

                // [Open Log] 链接
                MutableComponent logLink = Component.literal(" [Open Log]")
                        .setStyle(Style.EMPTY
                                .withClickEvent(new ClickEvent(
                                        ClickEvent.Action.OPEN_FILE,
                                        RainJavaLogger.logFile(type).toAbsolutePath().toString()))
                                .withColor(ChatFormatting.AQUA)
                                .withUnderlined(true));

                // [View Error Screen] 链接
                // 点击后在客户端执行 /rainjava_errors <type>，该命令由 RainJavaClientEvents 注册，
                // 属于纯客户端命令，Forge ClientCommandHandler 会在发往服务器前拦截执行。
                MutableComponent screenLink = Component.literal(" [View Error Screen]")
                        .setStyle(Style.EMPTY
                                .withClickEvent(new ClickEvent(
                                        ClickEvent.Action.RUN_COMMAND,
                                        "/rainjava_errors " + type.getName()))
                                .withColor(ChatFormatting.RED)
                                .withUnderlined(true));

                src.sendSuccess(() -> Component.literal("").append(logLink).append(screenLink), false);
            }
        } catch (Exception e) {
            feedback(src, ChatFormatting.RED,
                    "✘ RainJava: Reload failed - " + e.getMessage());
            RainJava.LOGGER.error("Reload failed for {}", type, e);
        }

        return 1;
    }

    private static int reloadAll(CommandContext<CommandSourceStack> ctx) {
        feedback(ctx.getSource(), ChatFormatting.YELLOW,
                "▶ RainJava: Reloading all scripts...");
        for (ScriptType type : ScriptType.values()) {
            reload(ctx, type);
        }
        return 1;
    }

    // -----------------------------------------------------------------------
    // hand 指令实现（保持原样）
    // -----------------------------------------------------------------------

    private static int handGetId(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        try {
            ServerPlayer player = src.getPlayerOrException();
            ItemStack stack = player.getMainHandItem();
            if (stack.isEmpty()) {
                feedback(src, ChatFormatting.RED, "You are not holding any item.");
                return 0;
            }
            String id = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
            MutableComponent label = Component.literal("Item ID: ").withStyle(ChatFormatting.GRAY);
            MutableComponent value = Component.literal(id).setStyle(Style.EMPTY
                    .withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, id))
                    .withUnderlined(true));
            MutableComponent hint = Component.literal(" (click to copy)").withStyle(ChatFormatting.DARK_GRAY);
            src.sendSuccess(() -> label.append(value).append(hint), false);
        } catch (Exception e) {
            feedback(src, ChatFormatting.RED, "Error: " + e.getMessage());
        }
        return 1;
    }

    private static int handGetClass(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        try {
            ServerPlayer player = src.getPlayerOrException();
            ItemStack stack = player.getMainHandItem();
            if (stack.isEmpty()) {
                feedback(src, ChatFormatting.RED, "You are not holding any item.");
                return 0;
            }
            String className = stack.getItem().getClass().getName();
            MutableComponent label = Component.literal("Item class: ").withStyle(ChatFormatting.GRAY);
            MutableComponent value = Component.literal(className).setStyle(Style.EMPTY
                    .withColor(ChatFormatting.GOLD)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, className))
                    .withUnderlined(true));
            MutableComponent hint = Component.literal(" (click to copy)").withStyle(ChatFormatting.DARK_GRAY);
            src.sendSuccess(() -> label.append(value).append(hint), false);
            String stackClass = stack.getClass().getName();
            if (!stackClass.equals(className)) {
                MutableComponent extra = Component.literal("ItemStack class: " + stackClass)
                        .withStyle(ChatFormatting.DARK_GRAY);
                src.sendSuccess(() -> extra, false);
            }
        } catch (Exception e) {
            feedback(src, ChatFormatting.RED, "Error: " + e.getMessage());
        }
        return 1;
    }

    // -----------------------------------------------------------------------
    // errors 指令实现（保持原样）
    // -----------------------------------------------------------------------

    private static int showErrors(CommandContext<CommandSourceStack> ctx, ScriptType type) {
        CommandSourceStack src = ctx.getSource();
        int errCount  = ScriptErrorCollector.getErrors(type).size();
        int warnCount = ScriptErrorCollector.getWarnings(type).size();

        if (errCount == 0 && warnCount == 0) {
            feedback(src, ChatFormatting.GREEN,
                    "✔ RainJava " + type.getName() + ": No errors or warnings.");
        } else {
            feedback(src, errCount > 0 ? ChatFormatting.RED : ChatFormatting.YELLOW,
                    "RainJava " + type.getName() + ": "
                            + errCount + " error(s), " + warnCount + " warning(s).");

            var errorList = ScriptErrorCollector.getErrors(type);
            int shown = Math.min(5, errorList.size());
            for (int i = 0; i < shown; i++) {
                var e = errorList.get(i);
                String line = "  [" + (i + 1) + "] "
                        + e.fileName
                        + (e.lineNumber > 0 ? ":" + e.lineNumber : "")
                        + " - " + e.message;
                feedback(src, ChatFormatting.RED, line);
            }
            if (errorList.size() > 5) {
                feedback(src, ChatFormatting.GRAY,
                        "  ... and " + (errorList.size() - 5) + " more. Check the log file.");
            }

            String logPath = RainJavaLogger.logFile(type).toAbsolutePath().toString();
            MutableComponent logLink = Component.literal("  ► Open " + type.getName() + ".log")
                    .setStyle(Style.EMPTY
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, logPath))
                            .withColor(ChatFormatting.AQUA)
                            .withUnderlined(true));
            src.sendSuccess(() -> logLink, false);
        }
        return 1;
    }

    private static int showAllErrors(CommandContext<CommandSourceStack> ctx) {
        for (ScriptType type : ScriptType.values()) {
            showErrors(ctx, type);
        }
        return 1;
    }

    // -----------------------------------------------------------------------
    // 工具方法
    // -----------------------------------------------------------------------

    private static void feedback(CommandSourceStack src, ChatFormatting color, String text) {
        MutableComponent msg = Component.literal(text).withStyle(color);
        if (color == ChatFormatting.RED) {
            src.sendFailure(msg);
        } else {
            src.sendSuccess(() -> msg, false);
        }
    }
}
