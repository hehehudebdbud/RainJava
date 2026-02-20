package net.rain.rainjava.logging;

import net.rain.rainjava.core.ScriptType;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 脚本错误与警告的全局收集器。
 *
 * 线程安全：可以从任意线程写入，从渲染线程读取。
 * JavaScriptLoader 在编译失败时调用 addError()，
 * RainJavaErrorScreen 通过 getErrors() / getWarnings() 读取数据并展示。
 *
 * 执行 /java reload 命令时，应先调用 clear(type) 清除旧数据。
 */
public class ScriptErrorCollector {

    /** 各脚本类型对应的错误列表 */
    private static final Map<ScriptType, List<ScriptError>> ERRORS   = new EnumMap<>(ScriptType.class);
    /** 各脚本类型对应的警告列表 */
    private static final Map<ScriptType, List<ScriptError>> WARNINGS = new EnumMap<>(ScriptType.class);

    static {
        for (ScriptType t : ScriptType.values()) {
            ERRORS.put(t,   new CopyOnWriteArrayList<>());
            WARNINGS.put(t, new CopyOnWriteArrayList<>());
        }
    }

    // 纯静态工具类，禁止实例化
    private ScriptErrorCollector() {}

    // -----------------------------------------------------------------------
    // 写入方法
    // -----------------------------------------------------------------------

    /**
     * 添加一条 ScriptError（根据其 type 自动分流到 ERRORS 或 WARNINGS）。
     * 同时会通过 RainJavaLogger 写入独立日志文件。
     */
    public static void addError(ScriptError e) {
        if (e.type == ScriptError.Type.ERROR) {
            ERRORS.get(e.scriptType).add(e);
        } else {
            WARNINGS.get(e.scriptType).add(e);
        }
        RainJavaLogger.printError(e.scriptType,
                "Script error in {}: {}", e.fileName, e.message);
    }

    /** 快捷方法：添加一条编译错误 */
    public static void addError(ScriptType type, String message, String file, long line) {
        addError(new ScriptError(ScriptError.Type.ERROR, type, message, file, line));
    }

    /** 快捷方法：添加一条编译警告 */
    public static void addWarning(ScriptType type, String message, String file, long line) {
        addError(new ScriptError(ScriptError.Type.WARN, type, message, file, line));
    }

    /** 快捷方法：从异常构建并添加一条错误记录 */
    public static void addFromThrowable(ScriptType type, String file, Throwable t) {
        addError(ScriptError.fromThrowable(ScriptError.Type.ERROR, type, file, t));
    }

    /**
     * 清除指定脚本类型的所有错误和警告。
     * 应在 reload 之前调用，保证显示的是最新结果。
     */
    public static void clear(ScriptType type) {
        ERRORS.get(type).clear();
        WARNINGS.get(type).clear();
    }

    // -----------------------------------------------------------------------
    // 读取方法
    // -----------------------------------------------------------------------

    /** 获取指定脚本类型的所有错误（只读视图） */
    public static List<ScriptError> getErrors(ScriptType type) {
        return Collections.unmodifiableList(ERRORS.get(type));
    }

    /** 获取指定脚本类型的所有警告（只读视图） */
    public static List<ScriptError> getWarnings(ScriptType type) {
        return Collections.unmodifiableList(WARNINGS.get(type));
    }

    /** 判断指定脚本类型是否有错误 */
    public static boolean hasErrors(ScriptType type) {
        return !ERRORS.get(type).isEmpty();
    }

    /** 判断是否有任意类型的错误 */
    public static boolean hasAnyErrors() {
        return ERRORS.values().stream().anyMatch(l -> !l.isEmpty());
    }

    /** 判断是否有任意类型的错误或警告 */
    public static boolean hasAnyIssues() {
        return ERRORS.values().stream().anyMatch(l -> !l.isEmpty())
                || WARNINGS.values().stream().anyMatch(l -> !l.isEmpty());
    }

    /**
     * 返回第一个存在错误的脚本类型，全部正常时返回 null。
     * 用于决定错误屏幕默认展示哪个类型。
     */
    public static ScriptType firstTypeWithErrors() {
        for (ScriptType t : ScriptType.values()) {
            if (!ERRORS.get(t).isEmpty()) return t;
        }
        return null;
    }
}
