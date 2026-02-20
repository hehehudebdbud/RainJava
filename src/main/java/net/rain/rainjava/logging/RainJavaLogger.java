package net.rain.rainjava.logging;

import net.minecraftforge.fml.loading.FMLPaths;
import net.rain.rainjava.core.ScriptType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RainJava 独立日志系统。
 *
 * 每个脚本类型（startup / server / client）拥有独立的日志文件，
 * 路径为 logs/Java/<类型>.log，同时也会输出到主游戏日志 latest.log。
 *
 * 修复：使用懒加载方式初始化 Writer，避免在 FML 就绪前调用 FMLPaths 导致
 * WRITERS 为空、所有独立日志写入静默失败的问题。
 */
public class RainJavaLogger {

    // -----------------------------------------------------------------------
    // 静态基础设施
    // -----------------------------------------------------------------------

    /** 主游戏日志（latest.log），仅用于镜像输出 */
    private static final Logger MAIN_LOGGER = LogManager.getLogger("RainJava");
    /** 时间戳格式 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 每个脚本类型对应一个独立的 PrintWriter */
    private static final Map<ScriptType, PrintWriter> WRITERS = new EnumMap<>(ScriptType.class);

    /** 是否已经完成过初始化尝试 */
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 单例缓存 */
    private static final Map<ScriptType, RainJavaLogger> INSTANCES = new EnumMap<>(ScriptType.class);

    /**
     * 确保日志文件已初始化。
     *
     * 采用懒加载而非 static 块，原因：
     * static 块在类加载时执行，此时 FMLPaths.GAMEDIR 可能尚未由 FML 设置，
     * 会导致 NullPointerException 或路径错误，进而使 WRITERS 全部为空，
     * 后续所有独立日志写入均静默失败，错误只出现在 latest.log。
     */
    private static void ensureInitialized() {
        // 使用 CAS 保证只初始化一次，多线程安全
        if (!initialized.compareAndSet(false, true)) return;
        initFiles();
    }

    /** 初始化 logs/Java/ 目录并为每个脚本类型创建日志文件 */
    private static void initFiles() {
        Path dir;
        try {
            dir = FMLPaths.GAMEDIR.get().resolve("logs").resolve("Java");
        } catch (Exception e) {
            MAIN_LOGGER.error("[RainJava] FMLPaths not ready yet, cannot initialize log files", e);
            // 重置标志，允许下次调用重试
            initialized.set(false);
            return;
        }

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            MAIN_LOGGER.error("[RainJava] Could not create logs/Java directory", e);
            return;
        }

        for (ScriptType type : ScriptType.values()) {
            Path file = dir.resolve(type.getName() + ".log");
            try {
                // append=false：每次游戏启动都创建新的日志文件
                PrintWriter pw = new PrintWriter(
                        new BufferedWriter(new FileWriter(file.toFile(), false)), true);
                WRITERS.put(type, pw);
                pw.printf("[%s] [%s/INFO] RainJava logger started%n",
                        LocalDateTime.now().format(TS), type.getName().toUpperCase());
                pw.flush();
            } catch (IOException e) {
                MAIN_LOGGER.error("[RainJava] Failed to open log file: {}", file, e);
            }
        }

        MAIN_LOGGER.info("[RainJava] Independent log files initialized in: {}", dir);
    }

    // -----------------------------------------------------------------------
    // 静态快捷方法
    // -----------------------------------------------------------------------

    /**
     * 获取指定脚本类型的单例 Logger。
     */
    public static RainJavaLogger of(ScriptType type) {
        return INSTANCES.computeIfAbsent(type, RainJavaLogger::new);
    }

    public static void printInfo(ScriptType t, String msg, Object... args) {
        write(t, Level.INFO, null, msg, args);
    }

    public static void printWarn(ScriptType t, String msg, Object... args) {
        write(t, Level.WARN, null, msg, args);
    }

    public static void printError(ScriptType t, String msg, Object... args) {
        write(t, Level.ERROR, null, msg, args);
    }

    public static void printDebug(ScriptType t, String msg, Object... args) {
        write(t, Level.DEBUG, null, msg, args);
    }

    /**
     * 将完整的编译器输出（包含多行错误信息）直接写入独立日志。
     * 由 JavaScriptLoader 在捕获到编译失败异常后调用，确保完整错误内容进入独立 log。
     *
     * @param type           目标脚本类型
     * @param compilerOutput 编译器输出的完整错误文本
     */
    public static void printCompilerOutput(ScriptType type, String compilerOutput) {
        ensureInitialized();
        String timestamp = LocalDateTime.now().format(TS);
        String tag       = type.getName().toUpperCase();

        PrintWriter pw = WRITERS.get(type);
        if (pw != null) {
            pw.printf("[%s] [%s/ERROR] === Compiler Output ===%n", timestamp, tag);
            // 逐行写入，保留编译器原始格式
            for (String line : compilerOutput.split("\n")) {
                pw.printf("[%s] [%s/ERROR] %s%n", timestamp, tag, line);
            }
            pw.printf("[%s] [%s/ERROR] === End of Compiler Output ===%n", timestamp, tag);
            pw.flush();
        }
        // 同时输出首行到 latest.log（避免刷屏，只打摘要）
        String firstLine = compilerOutput.lines().findFirst().orElse("(no output)");
        MAIN_LOGGER.error("[{}] Compilation failed: {}", tag, firstLine);
    }

    /**
     * 获取指定脚本类型的日志文件路径。
     */
    public static Path logFile(ScriptType type) {
        return FMLPaths.GAMEDIR.get().resolve("logs").resolve("Java")
                .resolve(type.getName() + ".log");
    }

    /** 关闭所有文件写入器（游戏关闭时调用） */
    public static void closeAll() {
        WRITERS.values().forEach(PrintWriter::close);
        WRITERS.clear();
        initialized.set(false);
    }

    // -----------------------------------------------------------------------
    // 实例 API
    // -----------------------------------------------------------------------

    /** 当前实例绑定的脚本类型 */
    private final ScriptType scriptType;

    public RainJavaLogger(ScriptType scriptType) {
        this.scriptType = scriptType;
    }

    public void info(String msg, Object... args)  { write(scriptType, Level.INFO,  null, msg, args); }
    public void warn(String msg, Object... args)  { write(scriptType, Level.WARN,  null, msg, args); }
    public void error(String msg, Object... args) { write(scriptType, Level.ERROR, null, msg, args); }
    public void debug(String msg, Object... args) { write(scriptType, Level.DEBUG, null, msg, args); }

    /** 带异常堆栈的 error 方法 */
    public void error(String msg, Throwable t, Object... args) {
        write(scriptType, Level.ERROR, t, msg, args);
    }

    public ScriptType getScriptType() { return scriptType; }

    // -----------------------------------------------------------------------
    // 核心写入逻辑
    // -----------------------------------------------------------------------

    private enum Level { INFO, WARN, ERROR, DEBUG }

    private static void write(ScriptType type, Level level, Throwable thrown,
                              String msg, Object[] args) {
        // 每次写入前确保已初始化（懒加载核心逻辑）
        ensureInitialized();

        String formatted = format(msg, args);
        String timestamp = LocalDateTime.now().format(TS);
        String tag       = type.getName().toUpperCase();

        // 写入独立日志文件
        PrintWriter pw = WRITERS.get(type);
        if (pw != null) {
            pw.printf("[%s] [%s/%s] %s%n", timestamp, tag, level, formatted);
            if (thrown != null) {
                thrown.printStackTrace(pw);
            }
            pw.flush();
        } else {
            // WRITERS 为空时回退到主日志，并附加警告提示
            MAIN_LOGGER.warn("[RainJava] Independent log writer not available for type: {}", type);
        }

        // 同步输出到主游戏日志（latest.log）
        String mainMsg = "[" + tag + "] " + formatted;
        switch (level) {
            case INFO  -> MAIN_LOGGER.info(mainMsg);
            case WARN  -> MAIN_LOGGER.warn(mainMsg);
            case ERROR -> {
                if (thrown != null) MAIN_LOGGER.error(mainMsg, thrown);
                else               MAIN_LOGGER.error(mainMsg);
            }
            case DEBUG -> MAIN_LOGGER.debug(mainMsg);
        }
    }

    /**
     * SLF4J 风格 {} 占位符替换。
     */
    private static String format(String pattern, Object[] args) {
        if (args == null || args.length == 0) return pattern;

        StringBuilder sb = new StringBuilder(pattern.length() + 64);
        int ai = 0, i = 0;
        while (i < pattern.length()) {
            if (i + 1 < pattern.length()
                    && pattern.charAt(i) == '{'
                    && pattern.charAt(i + 1) == '}') {
                if (ai < args.length) {
                    Object arg = args[ai++];
                    if (arg instanceof Throwable th) {
                        StringWriter sw = new StringWriter();
                        th.printStackTrace(new PrintWriter(sw));
                        sb.append(sw);
                    } else {
                        sb.append(arg);
                    }
                } else {
                    sb.append("{}");
                }
                i += 2;
            } else {
                sb.append(pattern.charAt(i++));
            }
        }
        return sb.toString();
    }
}
