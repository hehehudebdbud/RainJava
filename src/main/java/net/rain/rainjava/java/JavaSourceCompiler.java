package net.rain.rainjava.java;

import javax.tools.*;
import java.io.*;
import net.rain.rainjava.java.helper.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject.Kind;
import net.minecraftforge.fml.loading.FMLPaths;
import org.eclipse.jdt.internal.compiler.tool.EclipseCompiler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 动态编译 Java 源码 */
public class JavaSourceCompiler {
    private final JavaCompiler compiler;
    private final List<String> options;
    private final boolean isEclipseCompiler;
    private final String classPath;
    public static final Logger LOGGER = LogManager.getLogger();

    public JavaSourceCompiler(javax.tools.JavaCompiler javaCompiler) {
        if (javaCompiler == null) {
            throw new RuntimeException(
            "No Java compiler available!\n" +
                    "Possible reasons:\n" +
                    "1. You are running on JRE instead of JDK\n" +
                    "2. The java.compiler module is not available\n" +
                    "Solution: Make sure you're using a JDK (not JRE) to run Minecraft/Forge"
            );
        }

        this.compiler = javaCompiler;
        this.isEclipseCompiler = javaCompiler instanceof EclipseCompiler;
        this.options = new ArrayList<>();

        // 设置编译选项
        options.add("-source");
        options.add("17");
        options.add("-target");
        options.add("17");
        options.add("-encoding");
        options.add("UTF-8");
        // options.add("-proc:none");
        options.add("-warn:none");
        // options.add("-proc:full");  // 或者用 -proc:full
        // addModuleExports();
        options.add("-processor");
        options.add("org.spongepowered.tools.obfuscation.MixinObfuscationProcessorInjection," +
                "org.spongepowered.tools.obfuscation.MixinObfuscationProcessorTargets");
        options.add("-A" + "mixin.env.remapRefMap=true");
        options.add("-A" + "mixin.env.disableTargetExport=true");
        options.add("-A" + "mixin.debug.export.decompile=false");
        options.add("-preserveAllLocals");
        options.add("-Xdiags:verbose");
        options.add("-enableJavadoc");
        options.add("-g:vars,lines,source");
        options.add("-proceedOnError");
        options.add("-XenableNullAnnotations");
        options.add("-XJavac");
        options.add("-XprintProcessorInfo");
        options.add("-verbose");

        // 添加完整的 classpath (包括所有运行时加载的 jar)
        String classPath = buildClassPath();
        this.classPath = classPath;
        options.add("-classpath");
        // options.add("-annotationpath");
        options.add(classPath);

        options.add("-processorpath");
        options.add(classPath);

        // 只有非 Eclipse 编译器才添加 -implicit:none
        if (!isEclipseCompiler) {
            options.add("-implicit:none");
        }
    }

    // -------------------------------------------------------------------------
    // 查找 Minecraft 根目录
    // -------------------------------------------------------------------------

    /**
     * 按优先级依次尝试多种途径定位 .minecraft 根目录。
     * 返回一个已存在的目录路径，若完全找不到则返回 null。
     */
    private static Path findMinecraftDirectory() {
        // 1. JVM 属性 minecraft.gameDir（启动器通常会设置）
        String gameDir = System.getProperty("minecraft.gameDir");
        if (gameDir != null) {
            Path p = Paths.get(gameDir);
            if (Files.exists(p)) {
                if (p.toString().contains("/versions/")) p = p.getParent().getParent();
                return p;
            }
        }

        // 2. 从 sun.java.command 命令行解析 --gameDir 参数
        try {
            String[] args = System.getProperty("sun.java.command", "").split(" ");
            for (int i = 0; i < args.length - 1; i++) {
                if (args[i].equals("--gameDir")) {
                    Path p = Paths.get(args[i + 1]);
                    if (Files.exists(p)) {
                        if (p.toString().contains("/versions/")) p = p.getParent().getParent();
                        return p;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 3. 从 java.class.path 中找含 .minecraft 的条目
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.contains(".minecraft")) {
                Path p = Paths.get(entry);
                while (p != null && !p.getFileName().toString().equals(".minecraft"))
                    p = p.getParent();
                if (p != null && Files.exists(p)) return p;
            }
        }

        // 4. 常见默认路径（含 Android/FCL、Linux、Windows、macOS）
        String home = System.getProperty("user.home");
        for (String s : new String[]{
                "/storage/emulated/0/FCL/.minecraft",
                home + "/.minecraft",
                home + "/AppData/Roaming/.minecraft",
                home + "/Library/Application Support/minecraft"
        }) {
            Path p = Paths.get(s);
            if (Files.exists(p)) return p;
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // 构建 Classpath
    // -------------------------------------------------------------------------

    /** 构建完整的 classpath，包括所有运行时加载的类 */
    private String buildClassPath() {
        Set<String> classpathEntries = new LinkedHashSet<>();

        // 1. 添加系统 classpath
        String systemClassPath = System.getProperty("java.class.path");
        if (systemClassPath != null && !systemClassPath.isEmpty()) {
            classpathEntries.addAll(Arrays.asList(systemClassPath.split(File.pathSeparator)));
        }

        // 2. 从当前类加载器获取 classpath
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        extractClassPath(classLoader, classpathEntries);

        // 3. 从系统类加载器获取
        ClassLoader sysLoader = ClassLoader.getSystemClassLoader();
        if (sysLoader != classLoader) {
            extractClassPath(sysLoader, classpathEntries);
        }

        // 4. 扫描所有 Forge 模组（核心功能）
        scanAllForgeMods(classpathEntries);

        // 5. 尝试查找 Minecraft 和 Forge 核心 jar
        findMinecraftJars(classpathEntries);

        // 6. 基于 findMinecraftDirectory() 递归添加所有 jar
        scanMinecraftRootJars(classpathEntries);

        // 7. 扫描 mods 文件夹（备用方案）
        scanModsFolder(classpathEntries);

        // 8. 扫描 libraries 文件夹
        scanLibrariesFolder(classpathEntries);

        return String.join(File.pathSeparator, classpathEntries);
    }

    /**
     * 利用 {@link #findMinecraftDirectory()} 定位根目录，然后递归将其下
     * 所有 .jar 文件（排除 -sources.jar）加入 classpath。
     */
    private void scanMinecraftRootJars(Set<String> classpathEntries) {
        Path mcRoot = findMinecraftDirectory();
        if (mcRoot == null) {
            LOGGER.warn("[JavaSourceCompiler] findMinecraftDirectory() returned null, skipping root jar scan.");
            return;
        }
        LOGGER.info("[JavaSourceCompiler] Scanning Minecraft root for jars: {}", mcRoot);
        int[] counter = {0};
        scanDirectoryForJars(mcRoot.toFile(), classpathEntries, counter, true);
        LOGGER.info("[JavaSourceCompiler] Added {} jars from Minecraft root.", counter[0]);
    }

    /**
     * 通用递归目录 JAR 扫描器。
     *
     * @param dir              起始目录
     * @param classpathEntries 目标集合
     * @param counter          计数器（counter[0] 累加）
     * @param skipSources      是否跳过 -sources.jar
     */
    private void scanDirectoryForJars(File dir, Set<String> classpathEntries,
                                      int[] counter, boolean skipSources) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectoryForJars(file, classpathEntries, counter, skipSources);
            } else if (file.getName().endsWith(".jar")) {
                if (skipSources && file.getName().endsWith("-sources.jar")) continue;
                if (classpathEntries.add(file.getAbsolutePath())) {
                    counter[0]++;
                    LOGGER.debug("[JavaSourceCompiler] Added jar: {}", file.getAbsolutePath());
                }
            }
        }
    }

    private void addModuleExports() {
        String[] moduleExports = {
                "org.spongepowered.mixin/org.spongepowered.tools.obfuscation=ALL-UNNAMED",
                "org.spongepowered.mixin/org.spongepowered.asm.mixin=ALL-UNNAMED",
                "org.spongepowered.mixin/org.spongepowered.asm.mixin.transformer=ALL-UNNAMED",
                "org.spongepowered.mixin/org.spongepowered.asm.service=ALL-UNNAMED"
        };
        for (String export : moduleExports) {
            options.add("--add-exports");
            options.add(export);
        }
        String[] moduleOpens = {
                "org.spongepowered.mixin/org.spongepowered.tools.obfuscation=ALL-UNNAMED"
        };
        for (String open : moduleOpens) {
            options.add("--add-opens");
            options.add(open);
        }
        LOGGER.info("[JavaSourceCompiler] Added module exports for Mixin annotation processor");
    }

    /** 扫描所有已加载的 Forge 模组 */
    private void scanAllForgeMods(Set<String> classpathEntries) {
        try {
            Class<?> modListClass = Class.forName("net.minecraftforge.fml.ModList");
            Object modList = modListClass.getMethod("get").invoke(null);

            java.lang.reflect.Method getModsMethod = modListClass.getMethod("getMods");
            List<?> mods = (List<?>) getModsMethod.invoke(modList);

            int addedCount = 0;
            for (Object mod : mods) {
                try {
                    java.lang.reflect.Method getModIdMethod = mod.getClass().getMethod("getModId");
                    String modId = (String) getModIdMethod.invoke(mod);

                    java.lang.reflect.Method getModFileMethod = mod.getClass().getMethod("getFile");
                    Object modFile = getModFileMethod.invoke(mod);

                    if (modFile != null) {
                        String jarPath = extractModFilePath(modFile, modId);
                        if (jarPath != null && classpathEntries.add(jarPath)) {
                            addedCount++;
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("[JavaSourceCompiler]   ✗ Failed to process mod: " + e.getMessage());
                }
            }
        } catch (ClassNotFoundException e) {
            LOGGER.warn("[JavaSourceCompiler] ModList class not found - not running in Forge environment?");
        } catch (Exception e) {
            LOGGER.warn("[JavaSourceCompiler] Failed to scan Forge mods: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** 从 ModFile 对象提取文件路径 */
    private String extractModFilePath(Object modFile, String modId) {
        try {
            // 方法 1: getFilePath()
            try {
                java.lang.reflect.Method getFilePathMethod = modFile.getClass().getMethod("getFilePath");
                Object filePath = getFilePathMethod.invoke(modFile);
                if (filePath != null) {
                    String path = filePath.toString();
                    if (filePath instanceof java.nio.file.Path) {
                        path = ((java.nio.file.Path) filePath).toAbsolutePath().toString();
                    }
                    return path;
                }
            } catch (NoSuchMethodException ignored) {}

            // 方法 2: getFile()
            try {
                java.lang.reflect.Method getFileMethod = modFile.getClass().getMethod("getFile");
                Object file = getFileMethod.invoke(modFile);
                if (file instanceof File) {
                    return ((File) file).getAbsolutePath();
                } else if (file != null) {
                    return file.toString();
                }
            } catch (NoSuchMethodException ignored) {}

            // 方法 3: getSecureJar()
            try {
                java.lang.reflect.Method getSecureJarMethod = modFile.getClass().getMethod("getSecureJar");
                Object secureJar = getSecureJarMethod.invoke(modFile);
                if (secureJar != null) {
                    java.lang.reflect.Method getPathMethod = secureJar.getClass().getMethod("getPrimaryPath");
                    Object path = getPathMethod.invoke(secureJar);
                    if (path instanceof java.nio.file.Path) {
                        return ((java.nio.file.Path) path).toAbsolutePath().toString();
                    }
                }
            } catch (NoSuchMethodException ignored) {}

            // 方法 4: 从 toString() 提取
            String str = modFile.toString();
            if (str.contains(".jar")) {
                int jarIdx = str.indexOf(".jar");
                if (jarIdx > 0) {
                    int startIdx = str.lastIndexOf("file:", jarIdx);
                    if (startIdx >= 0) {
                        String extracted = str.substring(startIdx + 5, jarIdx + 4);
                        if (new File(extracted).exists()) {
                            return extracted;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[JavaSourceCompiler] Could not extract path for mod " + modId + ": " + e.getMessage());
        }
        return null;
    }

    /** 直接扫描 mods 文件夹 */
    private void scanModsFolder(Set<String> classpathEntries) {
        try {
            List<File> possibleModsFolders = new ArrayList<>();

            // 1. 优先从 findMinecraftDirectory() 获取
            Path mcRoot = findMinecraftDirectory();
            if (mcRoot != null) {
                possibleModsFolders.add(mcRoot.resolve("mods").toFile());
            }

            // 2. 当前工作目录下的 mods
            String workDir = System.getProperty("user.dir");
            if (workDir != null) {
                possibleModsFolders.add(new File(workDir, "mods"));
            }

            // 3. minecraft.gameDir 属性
            String gameDir = System.getProperty("minecraft.gameDir");
            if (gameDir != null) {
                possibleModsFolders.add(new File(gameDir, "mods"));
            }

            // 4. FMLPaths
            Path modsdir = FMLPaths.GAMEDIR.get().resolve("mods");
            if (modsdir != null) {
                possibleModsFolders.add(modsdir.toFile());
            }

            int addedCount = 0;
            for (File modsFolder : possibleModsFolders) {
                if (modsFolder.exists() && modsFolder.isDirectory()) {
                    File[] modFiles = modsFolder.listFiles((dir, name) ->
                            name.endsWith(".jar") && !name.endsWith("-sources.jar"));
                    if (modFiles != null) {
                        for (File modFile : modFiles) {
                            if (classpathEntries.add(modFile.getAbsolutePath())) {
                                addedCount++;
                            }
                        }
                    }
                }
            }
            LOGGER.info("[JavaSourceCompiler] Added {} jars from mods folders.", addedCount);
        } catch (Exception e) {
            LOGGER.warn("[JavaSourceCompiler] Failed to scan mods folder: " + e.getMessage());
        }
    }

    /** 扫描 libraries 文件夹获取依赖（递归添加所有 jar） */
    private void scanLibrariesFolder(Set<String> classpathEntries) {
        try {
            // 优先使用 findMinecraftDirectory()
            Path mcRoot = findMinecraftDirectory();
            File libDir = (mcRoot != null) ? mcRoot.resolve("libraries").toFile() : null;

            // 备用路径
            if (libDir == null || !libDir.exists()) {
                String gameDir = System.getProperty("minecraft.gameDir");
                if (gameDir != null) libDir = new File(gameDir, "libraries");
            }
            if (libDir == null || !libDir.exists()) {
                libDir = new File("/storage/emulated/0/FCL/.minecraft/libraries");
            }

            if (libDir != null && libDir.exists() && libDir.isDirectory()) {
                int[] addedCount = {0};
                // 直接用通用递归扫描器，跳过 -sources.jar
                scanDirectoryForJars(libDir, classpathEntries, addedCount, true);
                LOGGER.info("[JavaSourceCompiler] Added {} jars from libraries folder.", addedCount[0]);
            } else {
                LOGGER.warn("[JavaSourceCompiler] Libraries folder not found.");
            }
        } catch (Exception e) {
            LOGGER.warn("[JavaSourceCompiler] Failed to scan libraries: " + e.getMessage());
        }
    }

    /** 查找 Minecraft 和 Forge 核心 jar */
    private void findMinecraftJars(Set<String> classpathEntries) {
        String[] knownClasses = {
                "net.minecraft.world.item.Item",
                "net.minecraft.world.level.block.Block",
                "net.minecraftforge.registries.ForgeRegistries",
                "net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext",
                "net.minecraftforge.registries.DeferredRegister",
                "net.minecraftforge.registries.RegistryObject"
        };

        int foundCount = 0;
        for (String className : knownClasses) {
            try {
                Class<?> clazz = Class.forName(className);

                // 方法 1: ProtectionDomain
                try {
                    java.security.ProtectionDomain pd = clazz.getProtectionDomain();
                    if (pd != null && pd.getCodeSource() != null) {
                        java.net.URL location = pd.getCodeSource().getLocation();
                        if (location != null) {
                            String path = resolveJarPath(location);
                            if (path != null && classpathEntries.add(path)) {
                                foundCount++;
                                continue;
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // 方法 2: 类资源路径
                String resourceName = "/" + className.replace('.', '/') + ".class";
                java.net.URL classUrl = clazz.getResource(resourceName);
                if (classUrl != null) {
                    String path = resolveJarPath(classUrl);
                    if (path != null && classpathEntries.add(path)) {
                        foundCount++;
                        continue;
                    }
                }

                // 方法 3: ClassLoader.getResource
                ClassLoader cl = clazz.getClassLoader();
                if (cl != null) {
                    String resPath = className.replace('.', '/') + ".class";
                    java.net.URL resUrl = cl.getResource(resPath);
                    if (resUrl != null) {
                        String path = resolveJarPath(resUrl);
                        if (path != null && classpathEntries.add(path)) {
                            foundCount++;
                        }
                    }
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Exception ignored) {}
        }
        LOGGER.info("[JavaSourceCompiler] Found {} Minecraft/Forge core jars.", foundCount);
    }

    /** 从 URL 解析实际的 JAR 文件路径，处理 union://, jar:file://, file:// 等特殊协议 */
    private String resolveJarPath(java.net.URL url) {
        if (url == null) return null;
        try {
            String urlStr = url.toString();

            // jar:file://
            if (urlStr.startsWith("jar:file:")) {
                int endIdx = urlStr.indexOf("!");
                if (endIdx > 0) {
                    String filePath = java.net.URLDecoder.decode(urlStr.substring(9, endIdx), "UTF-8");
                    File file = new File(filePath);
                    if (file.exists()) return file.getAbsolutePath();
                }
            }

            // union:// (Forge SecureJar)
            if (urlStr.startsWith("union:")) {
                String remaining = urlStr.substring(6)
                        .replaceAll("%23\\d+!", "")
                        .replaceAll("!.*$", "");
                if (remaining.startsWith("//")) remaining = remaining.substring(2);
                remaining = java.net.URLDecoder.decode(remaining, "UTF-8");
                File file = new File(remaining);
                if (file.exists()) return file.getAbsolutePath();
            }

            // file://
            if (urlStr.startsWith("file:")) {
                String filePath = urlStr.substring(5);
                if (filePath.startsWith("//")) filePath = filePath.substring(2);
                filePath = java.net.URLDecoder.decode(filePath, "UTF-8");
                int exclamIdx = filePath.indexOf("!");
                if (exclamIdx > 0) filePath = filePath.substring(0, exclamIdx);
                File file = new File(filePath);
                if (file.exists()) return file.getAbsolutePath();
            }
        } catch (Exception e) {
            LOGGER.debug("[JavaSourceCompiler] Could not resolve jar path from: " + url + " - " + e.getMessage());
        }
        return null;
    }

    public String getClassPath() {
        return this.classPath;
    }

    /** 从类加载器中提取 classpath */
    private void extractClassPath(ClassLoader classLoader, Set<String> classpathEntries) {
        if (classLoader == null) return;
        try {
            if (classLoader instanceof java.net.URLClassLoader) {
                java.net.URLClassLoader urlClassLoader = (java.net.URLClassLoader) classLoader;
                for (java.net.URL url : urlClassLoader.getURLs()) {
                    try {
                        classpathEntries.add(new File(url.toURI()).getAbsolutePath());
                    } catch (Exception e) {
                        String urlStr = url.toString();
                        if (urlStr.startsWith("file:")) classpathEntries.add(urlStr.substring(5));
                    }
                }
            }

            // Java 9+ 内部类加载器
            try {
                Class<?> builtinLoaderClass = Class.forName("jdk.internal.loader.BuiltinClassLoader");
                if (builtinLoaderClass.isInstance(classLoader)) {
                    java.lang.reflect.Field ucpField = builtinLoaderClass.getDeclaredField("ucp");
                    ucpField.setAccessible(true);
                    Object ucp = ucpField.get(classLoader);
                    java.lang.reflect.Method getURLsMethod = ucp.getClass().getMethod("getURLs");
                    java.net.URL[] urls = (java.net.URL[]) getURLsMethod.invoke(ucp);
                    for (java.net.URL url : urls) {
                        try {
                            classpathEntries.add(new File(url.toURI()).getAbsolutePath());
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}

        extractClassPath(classLoader.getParent(), classpathEntries);
    }

    // -------------------------------------------------------------------------
    // 编译接口
    // -------------------------------------------------------------------------

    /** 编译 Java 源文件 */
    public CompiledClass compile(Path sourceFile) throws Exception {
        if (!Files.exists(sourceFile)) {
            throw new IllegalArgumentException("Source file does not exist: " + sourceFile.toAbsolutePath());
        }
        String source = Files.readString(sourceFile);
        String className = extractClassName(source);
        if (className == null || className.isEmpty()) {
            throw new IllegalArgumentException("Could not extract class name from source file: " + sourceFile);
        }
        return compileFromString(className, source, sourceFile.toString());
    }

    public CompiledClass compileFromString(String className, String source) throws Exception {
        LOGGER.info("Compiling from string: {}", className);
        LOGGER.debug("Source code length: {} characters", source.length());
        CompiledClass result = compileFromString(className, source, className + ".java");
        if (result == null) {
            LOGGER.error("Compilation returned null for class: {}", className);
            return null;
        }
        if (result.bytecode == null || result.bytecode.length == 0) {
            LOGGER.error("Compilation produced no bytecode for class: {}", className);
            return null;
        }
        LOGGER.info("Successfully compiled: {} ({} bytes)", className, result.bytecode.length);
        return result;
    }

    /** 从字符串编译 Java 代码 */
    public CompiledClass compileFromString(String className, String source, String sourceName)
            throws Exception {
        JavaFileObject sourceFileObject;
        if (isEclipseCompiler) {
            sourceFileObject = new EclipseCompatibleJavaFileObject(className, source);
        } else {
            sourceFileObject = new InMemoryJavaFileObject(className, source);
        }

        CustomFileManager fileManager = new CustomFileManager(isEclipseCompiler);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, diagnostics, options, null,
                Collections.singletonList(sourceFileObject)
        );

        boolean success = task.call();

        if (!success) {
            StringBuilder errors = new StringBuilder();
            errors.append("Compilation failed for: ").append(sourceName).append("\n");
            errors.append("Class name: ").append(className).append("\n");
            errors.append("Compiler: ").append(isEclipseCompiler ? "Eclipse JDT" : "System Java Compiler").append("\n");
            errors.append("Errors:\n");
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                errors.append("  Line ").append(diagnostic.getLineNumber())
                        .append(": ").append(diagnostic.getMessage(null)).append("\n");
            }
            throw new RuntimeException(errors.toString());
        }

        byte[] bytecode = fileManager.getCompiledClass(className);
        if (bytecode == null) {
            LOGGER.error("[JavaSourceCompiler] ERROR: No bytecode generated!");
            LOGGER.error("[JavaSourceCompiler] Requested class: " + className);
            LOGGER.error("[JavaSourceCompiler] Available outputs: " + fileManager.outputFiles.keySet());
            throw new RuntimeException("No bytecode generated for class: " + className +
                    ". Available: " + fileManager.outputFiles.keySet());
        }

        return new CompiledClass(className, bytecode);
    }

    /** 从源码中提取类名 */
    private String extractClassName(String source) {
        try {
            String packageName = "";
            String className = "";
            String cleanSource = source.replaceAll("//.*", "").replaceAll("/\\*.*?\\*/", "");

            int packageIndex = cleanSource.indexOf("package ");
            if (packageIndex >= 0) {
                int semicolon = cleanSource.indexOf(";", packageIndex);
                if (semicolon > packageIndex) {
                    packageName = cleanSource.substring(packageIndex + 8, semicolon).trim();
                }
            }

            String[] keywords = {"class ", "interface ", "enum ", "record "};
            for (String keyword : keywords) {
                int idx = cleanSource.indexOf(keyword);
                if (idx >= 0) {
                    String after = cleanSource.substring(idx + keyword.length());
                    String[] tokens = after.split("[\\s{<]");
                    if (tokens.length >= 1 && !tokens[0].trim().isEmpty()) {
                        className = tokens[0].trim();
                        break;
                    }
                }
            }

            if (className.isEmpty()) return null;
            return packageName.isEmpty() ? className : packageName + "." + className;
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // 内部文件对象 / 文件管理器
    // -------------------------------------------------------------------------

    /** Eclipse JDT 兼容的 Java 文件对象 */
    private static class EclipseCompatibleJavaFileObject extends SimpleJavaFileObject {
        private final String code;
        private final String className;

        public EclipseCompatibleJavaFileObject(String className, String code) {
            super(createVirtualURI(className), Kind.SOURCE);
            this.code = code;
            this.className = className;
        }

        private static URI createVirtualURI(String className) {
            String path = "/virtual/" + className.replace('.', '/') + ".java";
            return URI.create("file://" + path);
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) { return code; }

        @Override
        public InputStream openInputStream() throws IOException {
            return new ByteArrayInputStream(code.getBytes("UTF-8"));
        }

        @Override
        public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
            return new StringReader(code);
        }

        @Override
        public String getName() { return className.replace('.', '/') + ".java"; }
    }

    /** 标准内存中的 Java 文件对象 */
    private static class InMemoryJavaFileObject extends SimpleJavaFileObject {
        private final String code;
        private ByteArrayOutputStream bytecode;

        public InMemoryJavaFileObject(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        public InMemoryJavaFileObject(String className, Kind kind) {
            super(URI.create("string:///" + className.replace('.', '/') + kind.extension), kind);
            this.code = null;
            this.bytecode = new ByteArrayOutputStream();
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) { return code; }

        @Override
        public OutputStream openOutputStream() { return bytecode; }

        public byte[] getBytes() { return bytecode != null ? bytecode.toByteArray() : null; }
    }

    /** 输出文件对象 - 用于接收编译后的字节码 */
    private static class OutputJavaFileObject extends SimpleJavaFileObject {
        private ByteArrayOutputStream bytecode;
        private final String className;

        public OutputJavaFileObject(String className, Kind kind) {
            super(URI.create("bytes:///" + className.replace('.', '/') + kind.extension), kind);
            this.className = className;
            this.bytecode = new ByteArrayOutputStream();
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            bytecode = new ByteArrayOutputStream();
            return bytecode;
        }

        @Override
        public Writer openWriter() throws IOException {
            throw new UnsupportedOperationException("Writing to class file as text is not supported");
        }

        public byte[] getBytes() { return bytecode != null ? bytecode.toByteArray() : null; }
    }

    /** 自定义文件管理器 */
    private class CustomFileManager implements JavaFileManager {
        private final Map<String, OutputJavaFileObject> outputFiles = new HashMap<>();
        private final StandardJavaFileManager standardManager;
        private final boolean isEclipse;

        public CustomFileManager(boolean isEclipseCompiler) {
            this.isEclipse = isEclipseCompiler;
            JavaCompiler systemCompiler = ToolProvider.getSystemJavaCompiler();
            if (systemCompiler == null) systemCompiler = new EclipseCompiler();
            this.standardManager = systemCompiler.getStandardFileManager(null, null, null);
        }

        public byte[] getCompiledClass(String className) {
            OutputJavaFileObject file = outputFiles.get(className);
            if (file != null && file.getBytes() != null && file.getBytes().length > 0) {
                return file.getBytes();
            }
            for (Map.Entry<String, OutputJavaFileObject> entry : outputFiles.entrySet()) {
                String key = entry.getKey();
                byte[] bytes = entry.getValue().getBytes();
                if ((key.equals(className) || key.startsWith(className + "$")) && bytes != null && bytes.length > 0) {
                    return bytes;
                }
            }
            for (OutputJavaFileObject output : outputFiles.values()) {
                byte[] bytes = output.getBytes();
                if (bytes != null && bytes.length > 0) return bytes;
            }
            return null;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                Kind kind, FileObject sibling) {
            if (kind == Kind.CLASS) {
                OutputJavaFileObject file = new OutputJavaFileObject(className, kind);
                outputFiles.put(className, file);
                return file;
            }
            try {
                return standardManager.getJavaFileForOutput(location, className, kind, sibling);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public JavaFileObject getJavaFileForInput(Location location, String className, Kind kind) {
            try {
                return standardManager.getJavaFileForInput(location, className, kind);
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public ClassLoader getClassLoader(Location location) {
            return standardManager.getClassLoader(location);
        }

        @Override
        public Iterable<JavaFileObject> list(Location location, String packageName,
                Set<Kind> kinds, boolean recurse) throws IOException {
            if (location == StandardLocation.SOURCE_PATH && kinds.contains(Kind.SOURCE)) {
                return Collections.emptyList();
            }
            return standardManager.list(location, packageName, kinds, recurse);
        }

        @Override
        public String inferBinaryName(Location location, JavaFileObject file) {
            if (file instanceof InMemoryJavaFileObject ||
                    file instanceof OutputJavaFileObject ||
                    file instanceof EclipseCompatibleJavaFileObject) {
                String uri = file.toUri().toString();
                int startIdx = uri.indexOf("///");
                if (startIdx == -1) startIdx = uri.lastIndexOf("/");
                if (startIdx != -1) {
                    String path = uri.substring(startIdx + (uri.indexOf("///") != -1 ? 3 : 1));
                    if (path.endsWith(".java")) path = path.substring(0, path.length() - 5);
                    else if (path.endsWith(".class")) path = path.substring(0, path.length() - 6);
                    return path.replace('/', '.');
                }
            }
            return standardManager.inferBinaryName(location, file);
        }

        @Override
        public boolean isSameFile(FileObject a, FileObject b) {
            return standardManager.isSameFile(a, b);
        }

        @Override
        public boolean handleOption(String current, Iterator<String> remaining) {
            return standardManager.handleOption(current, remaining);
        }

        @Override
        public boolean hasLocation(Location location) {
            return standardManager.hasLocation(location);
        }

        @Override
        public FileObject getFileForInput(Location location, String packageName, String relativeName)
                throws IOException {
            return standardManager.getFileForInput(location, packageName, relativeName);
        }

        @Override
        public FileObject getFileForOutput(Location location, String packageName,
                String relativeName, FileObject sibling) throws IOException {
            return standardManager.getFileForOutput(location, packageName, relativeName, sibling);
        }

        @Override
        public void flush() throws IOException { standardManager.flush(); }

        @Override
        public void close() throws IOException { standardManager.close(); }

        @Override
        public int isSupportedOption(String option) {
            return standardManager.isSupportedOption(option);
        }
    }
}
