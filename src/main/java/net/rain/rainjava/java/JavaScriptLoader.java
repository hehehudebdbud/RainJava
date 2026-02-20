package net.rain.rainjava.java;

import net.rain.rainjava.RainJava;
import net.rain.rainjava.core.ScriptType;
import net.rain.rainjava.java.helper.*;
import net.rain.rainjava.java.transformer.McpToSrgTransformer;
import net.rain.rainjava.logging.RainJavaLogger;
import net.rain.rainjava.logging.ScriptErrorCollector;
import org.eclipse.jdt.internal.compiler.tool.EclipseCompiler;

import java.nio.file.*;
import java.util.*;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.stream.Stream;

/**
 * Java 脚本加载器，负责： 1. 扫描指定目录下的 .java 文件（排除 mixins/ 和 replace/ 子目录） 2. 应用 MCP → SRG 名称转换 3. 通过
 * JavaSourceCompiler 在内存中动态编译 4. 使用 DynamicClassLoader 加载编译产物 5. 调用各类的 init() 等初始化方法
 *
 * <p>所有日志均写入对应脚本类型的独立日志文件（logs/Java/<type>.log）。 编译错误会被提交到 {@link ScriptErrorCollector} 以驱动错误屏幕显示。
 */
public class JavaScriptLoader {

    /** 绑定的脚本类型 */
    private final ScriptType scriptType;

    /** 独立文件 Logger（写入 logs/Java/<type>.log） */
    private final RainJavaLogger logger;

    /** Java 源码编译器（系统 JDK 编译器或 Eclipse JDT 编译器） */
    private final JavaSourceCompiler compiler;

    /** 动态类加载器，用于加载编译产物 */
    private final DynamicClassLoader classLoader;

    /** 本次加载会话中已成功加载的类列表 */
    private final List<Class<?>> loadedClasses;

    /** 类替换管理器（处理 replace/ 目录中的类替换文件） */
    // private final ClassReplacementManager replacementManager;
    /** MCP 到 SRG 名称映射转换器 */
    private final McpToSrgTransformer mcpTransformer;

    // -----------------------------------------------------------------------
    // 构造与初始化
    // -----------------------------------------------------------------------

    public JavaScriptLoader(ScriptType scriptType) {
        this.scriptType = scriptType;
        this.logger = new RainJavaLogger(scriptType);

        // 打开必要的模块访问权限（Mixin 相关）
        RuntimeModuleOpener.openMixinModules();

        //不使用java系统编译器，以避免系统编译器和ecj不同
        JavaCompiler systemCompiler = null;
        if (systemCompiler == null) {
            try {
                systemCompiler = new EclipseCompiler();
                logger.info("Using Eclipse JDT compiler");
            } catch (Exception e) {
                logger.error("Failed to initialize Eclipse JDT compiler: {}", e.getMessage(), e);
                systemCompiler = null;
            }
        }

        if (systemCompiler == null) {
            // 两种编译器都不可用，禁用动态编译功能
            logger.error("No Java compiler available! Dynamic Java compilation is disabled.");
            this.compiler = null;
            this.classLoader = null;
            this.loadedClasses = new ArrayList<>();
            //this.replacementManager = null;
            this.mcpTransformer = null;
            return;
        }

        try {
            this.compiler = new JavaSourceCompiler(systemCompiler);
            this.classLoader = new DynamicClassLoader(Thread.currentThread().getContextClassLoader());
            this.loadedClasses = new ArrayList<>();
            this.mcpTransformer = new McpToSrgTransformer();
            logger.info("MCP to SRG transformer initialized");

            // 初始化类替换管理器，监控 RainJava/replace/ 目录
            Path gameDir = Paths.get(".").toAbsolutePath().normalize();
            Path rainJavaDir = gameDir.resolve("RainJava");
            // this.replacementManager = new ClassReplacementManager(compiler, rainJavaDir);

            logger.info("Java script loader initialized for: {}", scriptType);
        } catch (Exception e) {
            logger.error("Failed to initialize Java script loader: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    // -----------------------------------------------------------------------
    // 公开入口方法
    // -----------------------------------------------------------------------

    /** 处理类替换文件（在普通脚本加载之前调用） */
    /*public void processClassReplacements() {
        if (replacementManager == null || compiler == null) return;
        logger.info("======== Processing class replacements ========");
        replacementManager.processReplacements();
    }
    */

    /** 处理 Mixin（预留接口，供后续扩展使用） */
    public void processMixins() {
        // Mixin 处理逻辑（保留接口供扩展）
    }

    /**
     * 扫描并加载指定目录下的所有 .java 脚本文件。 mixins/ 和 replace/ 子目录下的文件会被跳过（由专用方法处理）。
     *
     * @param directory 要扫描的脚本目录
     */
    public void loadJavaScripts(Path directory) {
        if (compiler == null) {
            logger.warn("Compiler not available, skipping script loading");
            return;
        }
        if (!Files.exists(directory)) {
            logger.info("Scripts directory does not exist: {}", directory);
            return;
        }

        // 递归扫描所有 .java 文件，排除特殊子目录
        List<Path> javaFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(path -> {
                String p = path.toString();
                boolean isMixin = p.contains("mixins" + java.io.File.separator) || p.contains("mixins/");
                boolean isReplace = p.contains("replace" + java.io.File.separator) || p.contains("replace/");
                return p.endsWith(".java") && !isMixin && !isReplace;
            }).forEach(javaFiles::add);
        } catch (Exception e) {
            logger.error("Failed to scan scripts directory {}: {}", directory, e.getMessage(), e);
            ScriptErrorCollector.addFromThrowable(scriptType, directory.toString(), e);
            return;
        }

        if (javaFiles.isEmpty()) {
            logger.info("No Java script files found in {}", directory);
            return;
        }

        logger.info("Found {} file(s) to compile in {}", javaFiles.size(), directory);

        // 逐文件编译并加载
        int success = 0;
        for (Path file : javaFiles) {
            if (loadJavaFileWithTransform(file)) success++;
        }

        logger.info("Compiled {}/{} file(s) successfully", success, javaFiles.size());

        // 执行所有已加载类的初始化方法
        if (!loadedClasses.isEmpty()) {
            logger.info("Executing {} loaded class(es)...", loadedClasses.size());
            executeLoadedClasses();
        }
    }

    // -----------------------------------------------------------------------
    // 单文件编译与加载
    // -----------------------------------------------------------------------

    /**
     * 对单个 .java 文件执行完整的"转换-编译-加载"流程： 1. 读取原始源码 2. 应用 MCP → SRG 转换 3. 提取完全限定类名 4. 内存编译 5. 动态加载编译产物
     *
     * @param file 要处理的 .java 文件路径
     * @return 成功返回 true，失败返回 false
     */
    private boolean loadJavaFileWithTransform(Path file) {
        String fileName = file.getFileName().toString();
        try {
            logger.info("Processing: {}", fileName);
            long start = System.currentTimeMillis();
            Path absPath = resolveFilePath(file);

            if (!Files.exists(absPath)) {
                logger.error("File not found: {}", absPath);
                ScriptErrorCollector.addError(scriptType,
                        "File not found: " + absPath, fileName, -1);
                return false;
            }

            // 步骤 1：读取源码
            String originalSource = Files.readString(absPath);

            // 步骤 2：MCP → SRG 名称转换（失败时使用原始源码）
            String source;
            try {
                source = mcpTransformer.transformSource(originalSource, fileName);
            } catch (Exception e) {
                logger.warn("MCP->SRG transform failed for {}, using original source: {}",
                        fileName, e.getMessage());
                source = originalSource;
            }

            // 步骤 3：从源码中提取完全限定类名
            String className = extractClassName(source, fileName);
            if (className == null || className.isBlank()) {
                logger.error("Could not extract class name from: {}", fileName);
                ScriptErrorCollector.addError(scriptType,
                        "Could not extract class name", fileName, -1);
                return false;
            }
            logger.info("  Class: {}", className);

            // 步骤 4：内存编译
            CompiledClass compiled;
            try {
                compiled = compiler.compileFromString(className, source);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                // 把完整编译器输出（含所有 "Line N: ..." 错误行）写入独立 log
                RainJavaLogger.printCompilerOutput(scriptType, msg);
                parseAndCollectErrors(scriptType, fileName, msg);
                return false;
            }

            if (compiled == null || compiled.bytecode == null || compiled.bytecode.length == 0) {
                logger.error("  Compilation produced no bytecode for: {}", className);
                ScriptErrorCollector.addError(scriptType,
                        "Compilation produced no bytecode", fileName, -1);
                return false;
            }

            long ms = System.currentTimeMillis() - start;

            // 步骤 5：加载编译产物到 JVM
            classLoader.addCompiledClass(compiled.className, compiled.bytecode);
            Class<?> clazz = classLoader.loadClass(compiled.className);
            loadedClasses.add(clazz);

            logger.info("  Load: {} loaded ", compiled.className);
            return true;

        } catch (Exception e) {
            logger.error("  FAIL: {}: {}", fileName, e.getMessage(), e);
            ScriptErrorCollector.addFromThrowable(scriptType, fileName, e);
            return false;
        }
    }

    /** 将编译器输出的错误消息解析为独立条目并提交到 ScriptErrorCollector。 支持 "Line N: <message>" 格式的编译器错误行。 */
    private static void parseAndCollectErrors(ScriptType type, String fileName,
            String compilerOutput) {
        if (compilerOutput == null) {
            ScriptErrorCollector.addError(type, "Unknown compilation error", fileName, -1);
            return;
        }

        String[] lines = compilerOutput.split("\n");
        boolean found = false;

        for (String line : lines) {
            line = line.trim();
            // 匹配 "Line N: some error message" 格式
            if (line.startsWith("Line ") && line.contains(":")) {
                try {
                    int colon = line.indexOf(':');
                    long lineNum = Long.parseLong(line.substring(5, colon).trim());
                    String msg = line.substring(colon + 1).trim();
                    ScriptErrorCollector.addError(type, msg, fileName, lineNum);
                    found = true;
                } catch (NumberFormatException ignored) {
                    ScriptErrorCollector.addError(type, line, fileName, -1);
                    found = true;
                }
            }
        }

        // 无法解析单独行时，存储完整消息
        if (!found) {
            ScriptErrorCollector.addError(type, compilerOutput, fileName, -1);
        }
    }

    // -----------------------------------------------------------------------
    // 类执行
    // -----------------------------------------------------------------------

    /** 依次执行所有已加载类的初始化方法 */
    private void executeLoadedClasses() {
        for (Class<?> clazz : loadedClasses) {
            try {
                executeClass(clazz);
            } catch (Exception e) {
                logger.error("Failed to execute {}: {}", clazz.getName(), e.getMessage(), e);
                ScriptErrorCollector.addFromThrowable(
                        scriptType, clazz.getSimpleName() + ".java", e);
            }
        }
    }

    /**
     * 对单个类执行初始化： 1. 优先查找无参 static init 方法 2. 其次查找带 FMLJavaModLoadingContext 参数的 static init 方法 3.
     * 最后尝试调用无参构造函数实例化
     */
    private void executeClass(Class<?> clazz) {
        try {
            Method initMethod = findInitMethod(clazz);
            if (initMethod != null) {
                logger.info("Executing {}() in {}", initMethod.getName(), clazz.getSimpleName());
                initMethod.invoke(null);
                return;
            }

            Method fmlMethod = findInitMethodWithFML(clazz);
            if (fmlMethod != null) {
                logger.info("Executing {}(FMLContext) in {}", fmlMethod.getName(), clazz.getSimpleName());
                fmlMethod.invoke(null,
                        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get());
                return;
            }

            // 无 init 方法时，尝试直接实例化
            if (!Modifier.isAbstract(clazz.getModifiers()) && !clazz.isInterface()) {
                try {
                    clazz.getDeclaredConstructor().newInstance();
                    logger.info("Instantiated: {}", clazz.getSimpleName());
                } catch (NoSuchMethodException e) {
                    logger.info("No init method or default constructor found in {}",
                            clazz.getSimpleName());
                }
            }
        } catch (Exception e) {
            logger.error("Error executing {}: {}", clazz.getName(), e.getMessage(), e);
        }
    }

    /** 在指定类中查找符合规范的无参 static 初始化方法。 支持的方法名（按优先级）：init、initialize、onLoad、load、register */
    private Method findInitMethod(Class<?> clazz) {
        for (String name : new String[]{"init", "initialize", "onLoad", "load", "register"}) {
            try {
                Method m = clazz.getDeclaredMethod(name);
                if (Modifier.isStatic(m.getModifiers()) &&
                        Modifier.isPublic(m.getModifiers()) &&
                        m.getParameterCount() == 0) {
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /** 在指定类中查找带 FMLJavaModLoadingContext 参数的 static 初始化方法。 用于需要访问 FML 上下文的脚本。 */
    private Method findInitMethodWithFML(Class<?> clazz) {
        for (String name : new String[]{"init", "initialize", "onLoad", "load", "register"}) {
            try {
                Method m = clazz.getDeclaredMethod(name,
                        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.class);
                if (Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers())) {
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // 工具方法
    // -----------------------------------------------------------------------

    /**
     * 从 Java 源码中提取完全限定类名（包名 + 类名）。 支持 class、interface、enum、record 等关键字。 失败时以文件名（去掉 .java 后缀）作为兜底。
     */
    private String extractClassName(String source, String fileName) {
        try {
            String pkg = "", cls = "";
            // 提取 package 声明
            int pi = source.indexOf("package ");
            if (pi != -1) {
                int semi = source.indexOf(';', pi);
                if (semi > pi) pkg = source.substring(pi + 8, semi).trim();
            }
            // 提取类名（查找第一个 class/interface 关键字）
            for (String kw : new String[]{
                    "public class ", "class ", "public interface ", "interface "
            }) {
                int ci = source.indexOf(kw);
                if (ci != -1) {
                    int si = ci + kw.length();
                    int end = source.length();
                    for (char stop : new char[]{' ', '{', '<', '\n', '\r'}) {
                        int idx = source.indexOf(stop, si);
                        if (idx > si) end = Math.min(end, idx);
                    }
                    cls = source.substring(si, end).trim();
                    break;
                }
            }
            if (cls.isEmpty()) {
                return fileName.endsWith(".java")
                        ? fileName.substring(0, fileName.length() - 5) : fileName;
            }
            return pkg.isEmpty() ? cls : pkg + "." + cls;
        } catch (Exception e) {
            return fileName.endsWith(".java")
                    ? fileName.substring(0, fileName.length() - 5) : fileName;
        }
    }

    /** 解析文件的绝对路径。 依次尝试：绝对路径 → 相对于工作目录的路径。 */
    private Path resolveFilePath(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        if (Files.exists(abs)) return abs;
        try {
            Path rel = Paths.get(".").toAbsolutePath().normalize().resolve(file).normalize();
            if (Files.exists(rel)) return rel;
        } catch (Exception ignored) {
        }
        return abs;
    }

    // -----------------------------------------------------------------------
    // Getter 方法
    // -----------------------------------------------------------------------

    /** 返回本次会话中已加载的类列表（防御性拷贝） */
    public List<Class<?>> getLoadedClasses() {
        return new ArrayList<>(loadedClasses);
    }

    /** 编译器是否可用（可用则 true） */
    public boolean isAvailable() {
        return compiler != null;
    }

    /** 获取类替换管理器 */
    // public ClassReplacementManager getReplacementManager() { return replacementManager; }
    /** 获取源码编译器 */
    public JavaSourceCompiler getCompiler() {
        return compiler;
    }

    /** 获取动态类加载器 */
    public DynamicClassLoader getClassLoader() {
        return classLoader;
    }

    /** 获取 MCP → SRG 转换器 */
    public McpToSrgTransformer getMcpTransformer() {
        return mcpTransformer;
    }

    /** 获取本实例绑定的独立 Logger */
    public RainJavaLogger getLogger() {
        return logger;
    }
}
