// 文件: net/rain/api/mixin/loader/MixinLoader.java
package net.rain.api.mixin.loader;

import net.rain.api.mixin.IMixin;
import net.rain.api.mixin.manager.MixinManager;
import net.rain.rainjava.java.*;
import net.rain.api.mixin.transformer.MixinTransformerPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Mixin加载器 - 集成Java源文件编译和Mixin注册
 * 从 RainJava/mixins 目录加载Mixin源文件
 */
public class MixinLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinLoader.class);
    private static boolean initialized = false;
    private static MixinTransformerPlugin transformerPlugin;
    private static DynamicClassLoader classLoader = new DynamicClassLoader(Thread.currentThread().getContextClassLoader());
    
    /**
     * 初始化Mixin系统（不注册插件，由外部注册）
     */
    public static void init() {
        if (initialized) return;
        
        try {
            // 只创建转换器插件实例，不注册
            transformerPlugin = new MixinTransformerPlugin();
            
            initialized = true;
            LOGGER.info("========================================");
            LOGGER.info("Mixin System Initialized");
            LOGGER.info("Transformer Plugin Created (external registration required)");
            LOGGER.info("========================================");
        } catch (Exception e) {
            LOGGER.error("Failed to initialize Mixin system", e);
            throw new RuntimeException(e);
        }
    }
    
    /**
     * 从 RainJava/mixins 目录加载所有Mixin
     * 
     * @param compiler JavaSourceCompiler实例
     * @param classLoader 类加载器
     */
    public static void loadMixinsFromRainJava(JavaSourceCompiler compiler, ClassLoader classLoader) {
        try {
            // 确定 RainJava/mixins 目录
            Path gameDir = Paths.get(".").toAbsolutePath().normalize();
            Path rainJavaDir = gameDir.resolve("RainJava");
            Path mixinsDir = rainJavaDir.resolve("mixins");
            
            if (!Files.exists(mixinsDir)) {
                LOGGER.info("Mixins directory does not exist: {}", mixinsDir);
                LOGGER.info("Creating directory: {}", mixinsDir);
                Files.createDirectories(mixinsDir);
                return;
            }
            
            loadMixinsWithCompiler(mixinsDir, compiler, classLoader);
            
        } catch (Exception e) {
            LOGGER.error("Failed to load mixins from RainJava directory", e);
        }
    }
    
    /**
     * 使用JavaSourceCompiler编译和加载Mixin
     * 
     * @param mixinsDir Mixin源文件目录
     * @param javaCompiler JavaSourceCompiler实例或任何具有compile方法的编译器
     * @param classLoader 类加载器
     */
    public static void loadMixinsWithCompiler(Path mixinsDir, JavaSourceCompiler javaCompiler, ClassLoader classLoader) {
        if (!Files.exists(mixinsDir)) {
            LOGGER.debug("Mixins directory does not exist: {}", mixinsDir);
            return;
        }
        
        LOGGER.info("========================================");
        LOGGER.info("Compiling and Loading Mixin Classes");
        LOGGER.info("Directory: {}", mixinsDir);
        LOGGER.info("========================================");
        
        List<Path> javaFiles = new ArrayList<>();
        
        // 扫描所有.java文件
        try (Stream<Path> paths = Files.walk(mixinsDir)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                 .forEach(javaFiles::add);
        } catch (Exception e) {
            LOGGER.error("Failed to scan mixins directory", e);
            return;
        }
        
        if (javaFiles.isEmpty()) {
            LOGGER.info("No mixin source files found in {}", mixinsDir);
            return;
        }
        
        LOGGER.info("Found {} mixin source file(s)", javaFiles.size());
        
        int successCount = 0;
        int failCount = 0;
        
        try {
            for (Path file : javaFiles) {
                try {
                    Path absolutePath = resolveFilePath(file);
                    CompiledClass compiled = javaCompiler.compile(absolutePath);
                    classLoader.addCompiledClass(compiled.className, compiled.bytecode);
                    Class<?> clazz = classLoader.loadClass(compiled.className);
                    // 注册Mixin
                    registerMixin(clazz);
                    successCount++;
                    
                    LOGGER.info("  ✓ Successfully loaded: {}", className);
                    
                } catch (Exception e) {
                    failCount++;
                    LOGGER.error("  ✗ Failed to compile: {}", file.getFileName(), e);
                }
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to setup compiler", e);
        }
        
        LOGGER.info("========================================");
        LOGGER.info("Mixin Compilation Complete");
        LOGGER.info("  Compiled: {}/{}", successCount, javaFiles.size());
        LOGGER.info("  Failed: {}", failCount);
        LOGGER.info("  Total Registered: {}", MixinManager.getTotalMixinCount());
        LOGGER.info("========================================");
    }
    
    /**
     * 使用反射定义类
     */
    private static Class<?> defineClass(ClassLoader classLoader, String className, byte[] bytecode) 
            throws Exception {
        java.lang.reflect.Method defineMethod = ClassLoader.class.getDeclaredMethod(
            "defineClass", String.class, byte[].class, int.class, int.class);
        defineMethod.setAccessible(true);
        
        return (Class<?>) defineMethod.invoke(classLoader, className, bytecode, 0, bytecode.length);
    }
    
    /**
     * 注册已编译的Mixin类
     */
    public static void registerMixin(Class<?> mixinClass) {
        if (!IMixin.class.isAssignableFrom(mixinClass)) {
            throw new IllegalArgumentException("Class must implement IMixin: " + mixinClass.getName());
        }
        
        MixinManager.registerMixin(mixinClass);
        LOGGER.debug("Registered mixin: {} targeting {}", 
            mixinClass.getSimpleName(), getMixinTarget(mixinClass));
    }
    
    /**
     * 批量注册Mixin类
     */
    public static void registerMixins(List<Class<?>> mixinClasses) {
        LOGGER.info("Registering {} mixin class(es)", mixinClasses.size());
        
        int registered = 0;
        for (Class<?> mixinClass : mixinClasses) {
            try {
                registerMixin(mixinClass);
                registered++;
            } catch (Exception e) {
                LOGGER.error("Failed to register mixin: {}", mixinClass.getName(), e);
            }
        }
        
        LOGGER.info("Successfully registered {}/{} mixins", registered, mixinClasses.size());
    }
    
    /**
     * 获取Mixin的目标类名
     */
    private static String getMixinTarget(Class<?> mixinClass) {
        try {
            IMixin mixin = (IMixin) mixinClass.getDeclaredConstructor().newInstance();
            return mixin.getTargetClass();
        } catch (Exception e) {
            return "unknown";
        }
    }
    
    /**
     * 清除所有已注册的Mixin
     */
    public static void clearAll() {
        MixinManager.clearAll();
        LOGGER.info("Cleared all registered mixins");
    }
    
    /**
     * 获取转换器插件实例（用于外部注册）
     */
    public static MixinTransformerPlugin getTransformerPlugin() {
        return transformerPlugin;
    }
    
    /**
     * 检查Mixin系统是否已初始化
     */
    public static boolean isInitialized() {
        return initialized;
    }
    
    private Path resolveFilePath(Path file) {
        Path absolutePath = file.toAbsolutePath().normalize();
        if (Files.exists(absolutePath)) {
            return absolutePath;
        }

        try {
            Path gameDir = Paths.get(".").toAbsolutePath().normalize();
            Path relativePath = gameDir.resolve(file).normalize();
            if (Files.exists(relativePath)) {
                return relativePath;
            }
        } catch (Exception ignored) {
        }

        if (!file.isAbsolute()) {
            try {
                Path currentDir = Paths.get("").toAbsolutePath();
                Path resolved = currentDir.resolve(file).normalize();
                if (Files.exists(resolved)) {
                    return resolved;
                }
            } catch (Exception ignored) {
            }
        }

        return absolutePath;
    }
}