package net.rain.rainjava.logging;

import net.rain.rainjava.core.ScriptType;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 脚本编译或运行期间产生的一条错误或警告记录。
 * 由 {@link ScriptErrorCollector} 收集，供 {@link net.rain.rainjava.client.RainJavaErrorScreen} 展示。
 */
public class ScriptError {

    /** 错误类型：编译错误或警告 */
    public enum Type { ERROR, WARN }

    /** 错误类型（ERROR / WARN） */
    public final Type type;
    /** 可读的错误信息（可能包含多行） */
    public final String message;
    /** 来源文件名，例如 "MyScript.java" */
    public final String fileName;
    /** 1-based 行号，未知时为 -1 */
    public final long lineNumber;
    /** 记录时间（Unix 毫秒） */
    public final long timestamp;
    /** 完整的堆栈跟踪行列表，无堆栈时为空列表 */
    public final List<String> stackTrace;
    /** 产生此错误的脚本类型 */
    public final ScriptType scriptType;

    public ScriptError(Type type, ScriptType scriptType,
                       String message, String fileName,
                       long lineNumber, List<String> stackTrace) {
        this.type       = type;
        this.scriptType = scriptType;
        this.message    = message;
        this.fileName   = fileName;
        this.lineNumber = lineNumber;
        this.timestamp  = System.currentTimeMillis();
        this.stackTrace = Collections.unmodifiableList(new ArrayList<>(stackTrace));
    }

    public ScriptError(Type type, ScriptType scriptType,
                       String message, String fileName, long lineNumber) {
        this(type, scriptType, message, fileName, lineNumber, List.of());
    }

    public ScriptError(Type type, ScriptType scriptType, String message, String fileName) {
        this(type, scriptType, message, fileName, -1, List.of());
    }

    /**
     * 从 Throwable 构建一条 ScriptError，自动捕获堆栈信息。
     *
     * @param type       错误类型
     * @param scriptType 脚本类型
     * @param fileName   来源文件名
     * @param t          捕获到的异常
     */
    public static ScriptError fromThrowable(Type type, ScriptType scriptType,
                                            String fileName, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        List<String> lines = List.of(sw.toString().split("\n"));
        String msg = (t.getMessage() != null) ? t.getMessage() : t.getClass().getName();
        return new ScriptError(type, scriptType, msg, fileName, -1, lines);
    }

    @Override
    public String toString() {
        return "[" + type + "] " + fileName + ":" + lineNumber + " - " + message;
    }
}
