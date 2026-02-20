package net.rain.rainjava.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.rain.rainjava.RainJava;
import net.rain.rainjava.core.ScriptType;
import net.rain.rainjava.logging.ScriptErrorCollector;

/**
 * 客户端事件处理器，负责在合适时机展示脚本错误信息。
 *
 * 展示策略：
 *  - STARTUP 类型错误：在 TitleScreen（主菜单）出现时直接弹出 ErrorScreen
 *  - SERVER / CLIENT 类型错误：玩家进入世界后，在聊天栏发送一条可点击消息，
 *    点击后执行 /rainjava_errors 命令打开 ErrorScreen（命令由外部注册）
 *
 * Reload 后通过 resetShownFlag() 重置标志，使提示可重新触发。
 */
@Mod.EventBusSubscriber(modid = RainJava.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class RainJavaClientEvents {

    /** STARTUP 错误是否已经弹出过屏幕（整个游戏生命周期只弹一次） */
    private static boolean startupScreenShown = false;

    /** SERVER/CLIENT 错误是否已经在聊天栏提示过（reload 后重置） */
    private static boolean worldNotified = false;

    // -----------------------------------------------------------------------
    // Tick 事件：适时触发提示
    // -----------------------------------------------------------------------

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;

        Minecraft mc = Minecraft.getInstance();

        // ── 1. STARTUP 错误：主菜单出现时直接弹出 ErrorScreen ──────────────
        if (!startupScreenShown && ScriptErrorCollector.hasErrors(ScriptType.STARTUP)) {
            if (mc.screen instanceof TitleScreen) {
                startupScreenShown = true;
                // 把 TitleScreen 作为 lastScreen，这样 ErrorScreen 关闭时回到主菜单
                // STARTUP 类型的 ErrorScreen 默认会强制退出游戏，而非关闭屏幕
                mc.setScreen(new RainJavaErrorScreen(mc.screen, ScriptType.STARTUP));
            }
            return; // STARTUP 处理完就退出，不混入下面的逻辑
        }

        // ── 2. SERVER / CLIENT 错误：进入世界后在聊天栏提示 ────────────────
        if (worldNotified) return;
        if (mc.player == null || mc.level == null) return; // 尚未进入世界

        // 检查是否有除 STARTUP 以外的错误或警告
        ScriptType typeWithErrors = firstNonStartupTypeWithErrors();
        boolean hasIssues = typeWithErrors != null || hasNonStartupIssues();
        if (!hasIssues) return;

        worldNotified = true;
        ScriptType target = typeWithErrors != null ? typeWithErrors : firstNonStartupTypeWithWarnings();
        if (target != null) {
            sendErrorChatMessage(mc, target);
        }
    }

    // -----------------------------------------------------------------------
    // 聊天消息构建
    // -----------------------------------------------------------------------

    /**
     * 向玩家聊天栏发送带点击链接的错误通知消息。
     *
     * 格式示例：
     *   [RainJava] server scripts: 2 error(s), 1 warning(s).  [点击查看]
     */
    private static void sendErrorChatMessage(Minecraft mc, ScriptType type) {
        int errCount  = ScriptErrorCollector.getErrors(type).size();
        int warnCount = ScriptErrorCollector.getWarnings(type).size();

        // 构建可点击的 "[点击查看详情]" 部分
        MutableComponent link = Component.literal("[Click to view errors]")
                .withStyle(s -> s
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(
                                ClickEvent.Action.RUN_COMMAND,
                                "/rainjava_errors " + type.getName())));

        // 拼合完整消息
        MutableComponent message = Component.literal("[RainJava] ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(type.getName() + " scripts: ")
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(errCount + " error(s)")
                        .withStyle(errCount > 0 ? ChatFormatting.RED : ChatFormatting.GRAY))
                .append(Component.literal(", ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(warnCount + " warning(s).  ")
                        .withStyle(warnCount > 0 ? ChatFormatting.YELLOW : ChatFormatting.GRAY))
                .append(link);

        mc.gui.getChat().addMessage(message);
    }

    // -----------------------------------------------------------------------
    // 辅助查询方法
    // -----------------------------------------------------------------------

    /** 返回第一个（非 STARTUP）存在错误的脚本类型，全部正常时返回 null */
    private static ScriptType firstNonStartupTypeWithErrors() {
        for (ScriptType t : ScriptType.values()) {
            if (t == ScriptType.STARTUP) continue;
            if (!ScriptErrorCollector.getErrors(t).isEmpty()) return t;
        }
        return null;
    }

    /** 返回第一个（非 STARTUP）存在警告的脚本类型 */
    private static ScriptType firstNonStartupTypeWithWarnings() {
        for (ScriptType t : ScriptType.values()) {
            if (t == ScriptType.STARTUP) continue;
            if (!ScriptErrorCollector.getWarnings(t).isEmpty()) return t;
        }
        return null;
    }

    /** 判断是否存在非 STARTUP 类型的错误或警告 */
    private static boolean hasNonStartupIssues() {
        for (ScriptType t : ScriptType.values()) {
            if (t == ScriptType.STARTUP) continue;
            if (!ScriptErrorCollector.getErrors(t).isEmpty()) return true;
            if (!ScriptErrorCollector.getWarnings(t).isEmpty()) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // 公开控制接口（供 RainJavaCommands 调用）
    // -----------------------------------------------------------------------

    /**
     * 重置世界通知标志，使 reload 后能重新发送聊天提示。
     * STARTUP 的屏幕标志不重置（startup 只在游戏启动时运行一次）。
     *
     * 由 {@link net.rain.rainjava.command.RainJavaCommands} 的 reload 指令调用。
     */
    public static void resetShownFlag() {
        worldNotified = false;
    }
}