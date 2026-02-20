package net.rain.rainjava.client;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.rain.rainjava.core.ScriptType;
import net.rain.rainjava.logging.RainJavaLogger;
import net.rain.rainjava.logging.ScriptError;
import net.rain.rainjava.logging.ScriptErrorCollector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 脚本编译错误展示屏幕，类似 KubeJS 的错误提示界面。
 *
 * 功能：
 *  - 滚动列表展示所有错误/警告
 *  - 鼠标悬停显示完整堆栈（按住 Shift 展示全部，否则最多 4 行）
 *  - 双击条目尝试在系统文件管理器中打开对应源文件
 *  - 右键双击复制堆栈到剪贴板
 *  - "Open Log File" 按钮打开独立日志文件
 *  - 错误/警告之间可切换查看
 *
 * 由 {@link RainJavaClientEvents} 在脚本加载完成后自动弹出。
 */
@OnlyIn(Dist.CLIENT)
public class RainJavaErrorScreen extends Screen {

    /** 每行条目的高度（像素） */
    private static final int ROW_HEIGHT = 52;
    /** 顶部标题区域高度 */
    private static final int HEADER_H   = 32;
    /** 底部按钮区域高度 */
    private static final int FOOTER_H   = 32;

    /** 返回的上一个屏幕（关闭时恢复） */
    @Nullable
    private final Screen lastScreen;
    /** 当前展示的脚本类型 */
    private final ScriptType scriptType;
    /** 该类型的所有错误 */
    private final List<ScriptError> errors;
    /** 该类型的所有警告 */
    private final List<ScriptError> warnings;

    /** 当前正在展示的列表（errors 或 warnings） */
    private List<ScriptError> viewing;
    /** 滚动列表组件 */
    private ErrorList list;

    // -----------------------------------------------------------------------
    // 构造
    // -----------------------------------------------------------------------

    public RainJavaErrorScreen(@Nullable Screen lastScreen, ScriptType type) {
        super(Component.empty());
        this.lastScreen = lastScreen;
        this.scriptType = type;
        this.errors     = new ArrayList<>(ScriptErrorCollector.getErrors(type));
        this.warnings   = new ArrayList<>(ScriptErrorCollector.getWarnings(type));
        // 若无错误但有警告，默认展示警告
        this.viewing    = errors.isEmpty() && !warnings.isEmpty() ? warnings : errors;
    }

    // -----------------------------------------------------------------------
    // 生命周期
    // -----------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();

        // 创建滚动错误列表
        int listBottom = this.height - FOOTER_H;
        this.list = new ErrorList(this, this.minecraft, this.width, this.height,
                HEADER_H, listBottom, viewing);
        addWidget(this.list);

        int btnY = this.height - FOOTER_H + 6;
        int cx   = this.width / 2;

        // 打开日志文件按钮
        Button openLog = addRenderableWidget(Button.builder(
                Component.literal("Open Log File"),
                b -> openLogFile()
        ).bounds(cx - 155, btnY, 150, 20).build());
        // 日志文件不存在时禁用按钮
        openLog.active = Files.exists(RainJavaLogger.logFile(scriptType));

        // 关闭/退出按钮（startup 类型时退出游戏，其余关闭屏幕）
        String closeLabel = (scriptType == ScriptType.STARTUP) ? "Quit Game" : "Close";
        addRenderableWidget(Button.builder(
                Component.literal(closeLabel),
                b -> quitOrClose()
        ).bounds(cx + 5, btnY, 150, 20).build());

        // 切换错误/警告视图按钮
        String toggleLabel = (viewing == errors)
                ? "View Warnings [" + warnings.size() + "]"
                : "View Errors ["  + errors.size()   + "]";
        Button toggle = addRenderableWidget(Button.builder(
                Component.literal(toggleLabel),
                b -> toggleView()
        ).bounds(this.width - 110, 7, 103, 18).build());
        // 只有在两者都存在时才允许切换
        toggle.active = !errors.isEmpty() && !warnings.isEmpty();
    }

    // -----------------------------------------------------------------------
    // 渲染
    // -----------------------------------------------------------------------

    @Override
    public void render(@NotNull GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        list.render(g, mx, my, delta);

        boolean isError = (viewing == errors);
        // 标题：显示脚本类型和当前查看的内容类型
        String title = "RainJava " + scriptType.getName() + " script "
                + (isError ? "errors" : "warnings");
        g.drawCenteredString(this.font, title, this.width / 2, 10,
                isError ? 0xFF5555 : 0xFFAA00);

        // 副标题：错误和警告数量概览
        String countInfo = errors.size() + " error(s)   " + warnings.size() + " warning(s)";
        g.drawCenteredString(this.font, countInfo, this.width / 2, 20, 0xAAAAAA);

        super.render(g, mx, my, delta);
    }

    // -----------------------------------------------------------------------
    // 行为
    // -----------------------------------------------------------------------

    /** startup 类型不允许按 ESC 关闭（强制处理错误） */
    @Override
    public boolean shouldCloseOnEsc() {
        return scriptType != ScriptType.STARTUP;
    }

    @Override
    public void onClose() {
        assert minecraft != null;
        minecraft.setScreen(lastScreen);
    }

    /** startup 类型时退出游戏，其余类型回到上一个屏幕 */
    private void quitOrClose() {
        if (scriptType == ScriptType.STARTUP) {
            assert minecraft != null;
            minecraft.stop();
        } else {
            onClose();
        }
    }

    /** 在系统中打开对应的独立日志文件 */
    private void openLogFile() {
        Path log = RainJavaLogger.logFile(scriptType);
        handleComponentClicked(
                Style.EMPTY.withClickEvent(
                        new ClickEvent(ClickEvent.Action.OPEN_FILE,
                                log.toAbsolutePath().toString())));
    }

    /** 切换当前展示的是错误还是警告列表，并重建界面组件 */
    private void toggleView() {
        viewing = (viewing == errors) ? warnings : errors;
        rebuildWidgets();
    }

    // -----------------------------------------------------------------------
    // 内部：滚动列表组件
    // -----------------------------------------------------------------------

    @OnlyIn(Dist.CLIENT)
    public static class ErrorList extends ObjectSelectionList<ErrorEntry> {

        /** 持有外层屏幕的引用，用于显示 tooltip */
        final RainJavaErrorScreen screen;

        public ErrorList(RainJavaErrorScreen screen, Minecraft mc,
                         int width, int height, int y0, int y1,
                         List<ScriptError> lines) {
            super(mc, width, height, y0, y1, ROW_HEIGHT);
            this.screen = screen;
            setRenderBackground(false);

            // 为每条记录创建一个列表条目
            for (int i = 0; i < lines.size(); i++) {
                addEntry(new ErrorEntry(this, mc, i, lines.get(i)));
            }
        }

        @Override
        public int getRowWidth() {
            return (int) (this.width * 0.93);
        }

        @Override
        protected int getScrollbarPosition() {
            return this.width - 6;
        }
    }

    // -----------------------------------------------------------------------
    // 内部：单条错误/警告条目
    // -----------------------------------------------------------------------

    @OnlyIn(Dist.CLIENT)
    public static class ErrorEntry extends ObjectSelectionList.Entry<ErrorEntry> {

        /** 用于格式化条目时间戳的日期格式 */
        private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss");

        private final ErrorList parent;
        private final Minecraft mc;
        private final ScriptError line;
        /** 上次点击时间，用于判断双击 */
        private long lastClickMs;

        /** 预渲染文本：序号 */
        private final FormattedCharSequence idxText;
        /** 预渲染文本：文件名及行号 */
        private final FormattedCharSequence fileText;
        /** 预渲染文本：时间戳 */
        private final FormattedCharSequence timeText;
        /** 预渲染文本：错误消息（最多 3 行） */
        private final List<FormattedCharSequence> msgText;
        /** 预渲染文本：堆栈信息（用于 tooltip） */
        private final List<FormattedCharSequence> traceText;

        public ErrorEntry(ErrorList parent, Minecraft mc, int index, ScriptError line) {
            this.parent = parent;
            this.mc     = mc;
            this.line   = line;

            this.idxText  = Component.literal("#" + (index + 1)).getVisualOrderText();
            this.fileText = Component.literal(
                    line.fileName + (line.lineNumber > 0 ? ":" + line.lineNumber : "")
            ).getVisualOrderText();
            this.timeText = Component.literal(SDF.format(new Date(line.timestamp)))
                    .withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText();

            // 将错误消息按宽度折行，最多保留 3 行
            List<FormattedCharSequence> msg = new ArrayList<>(mc.font.split(
                    Component.literal(line.message != null ? line.message : "(no message)"),
                    parent.getRowWidth() - 8));
            if (msg.size() > 3) msg.subList(3, msg.size()).clear();
            this.msgText = msg;

            // 堆栈信息：用于悬停 tooltip
            if (!line.stackTrace.isEmpty()) {
                this.traceText = mc.font.split(
                        Component.literal(String.join("\n", line.stackTrace))
                                .withStyle(ChatFormatting.GRAY),
                        Integer.MAX_VALUE);
            } else {
                this.traceText = List.of();
            }
        }

        @Override
        public @NotNull Component getNarration() {
            return Component.empty();
        }

        @Override
        public void render(@NotNull GuiGraphics g, int idx, int y, int x, int w, int h,
                           int mx, int my, boolean hovered, float delta) {
            // 错误用红色，警告用橙色
            int col = (line.type == ScriptError.Type.ERROR) ? 0xFF5B63 : 0xFFBB5B;

            // 鼠标悬停时绘制高亮背景
            if (hovered) {
                g.fill(x, y, x + w, y + h, 0x22FFFFFF);
            }

            // 第一行：序号 | 文件名:行号（居中）| 时间戳（右对齐）
            g.drawString(mc.font, idxText, x + 2, y + 2, col);
            g.drawCenteredString(mc.font, fileText, x + w / 2, y + 2, 0xFFFFFF);
            int tsW = mc.font.width(timeText);
            g.drawString(mc.font, timeText, x + w - tsW - 4, y + 2, 0x666666);

            // 后续行：错误消息
            for (int i = 0; i < msgText.size(); i++) {
                g.drawString(mc.font, msgText.get(i), x + 2, y + 14 + i * 10, col);
            }

            // 悬停时显示堆栈 tooltip（Shift=全量，否则最多 4 行）
            if (hovered && !traceText.isEmpty()) {
                List<FormattedCharSequence> shown = Screen.hasShiftDown()
                        ? traceText
                        : traceText.subList(0, Math.min(4, traceText.size()));
                parent.screen.setTooltipForNextRenderPass(shown);
            }
        }

        @Override
        public boolean mouseClicked(double mx, double my, int btn) {
            parent.setSelected(this);
            long now = Util.getMillis();
            if (now - lastClickMs < 250L) {
                // 双击：右键复制堆栈，左键打开文件
                if (btn == 1) {
                    mc.keyboardHandler.setClipboard(String.join("\n", line.stackTrace));
                } else {
                    openFile();
                }
                return true;
            }
            lastClickMs = now;
            return true;
        }

        /**
         * 尝试在操作系统文件管理器中打开对应的脚本源文件。
         * 路径构建规则：RainJava/<scriptType>/<fileName>
         */
        private void openFile() {
            if (line.fileName == null || line.fileName.isBlank()) return;

            Path base = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                    .resolve("RainJava")
                    .resolve(line.scriptType.getName())
                    .resolve(line.fileName);

            if (!Files.exists(base)) return;

            try {
                Desktop desk = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
                if (desk != null && desk.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                    desk.browseFileDirectory(base.toFile());
                    return;
                }
            } catch (Exception ignored) {}

            // 降级方案：通过游戏内点击事件打开
            parent.screen.handleComponentClicked(
                    Style.EMPTY.withClickEvent(
                            new ClickEvent(ClickEvent.Action.OPEN_FILE,
                                    base.toAbsolutePath().toString())));
        }
    }
}
