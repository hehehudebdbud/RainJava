package net.rain.rainjava.java;

import net.rain.rainjava.RainJava;
import net.rain.rainjava.java.helper.*;
import org.spongepowered.rain.asm.mixin.transformer.*;
import org.spongepowered.rain.asm.mixin.transformer.ext.Extensions;
import java.nio.file.*;
import java.util.*;

/**
 * 动态 Mixin 加载器 - 包装器方案
 * 通过包装 BytecodeProvider 拦截类加载请求
 */
public class DynamicMixinLoader {
    private final JavaSourceCompiler compiler;
    private final DynamicClassLoader classLoader;
    private final Path mixinsDir;
    
    private static volatile boolean initialized = false;
    private static final Object LOCK = new Object();
    
    public DynamicMixinLoader(JavaSourceCompiler compiler, DynamicClassLoader classLoader, Path rainJavaDir) {
        this.compiler = compiler;
        this.classLoader = classLoader;
        this.mixinsDir = rainJavaDir.resolve("mixins");
    }
    
    public void loadDynamicMixins() {
        if (initialized) {
            RainJava.LOGGER.info("DynamicMixinLoader already initialized");
            return;
        }
        
        synchronized (LOCK) {
            if (initialized) return;
            
            if (!Files.exists(mixinsDir)) {
                RainJava.LOGGER.info("Mixins directory does not exist: {}", mixinsDir);
                initialized = true;
                return;
            }
            
            RainJava.LOGGER.info("========================================");
            RainJava.LOGGER.info("Loading Dynamic Mixins (Wrapper Method)");
            RainJava.LOGGER.info("========================================");
            
            MixinProcessor processor = MixinProcessorHolder.getInstance();
            if (processor == null) {
                RainJava.LOGGER.error("MixinProcessor not available");
                return;
            }
            
            try {
                // ✅ 第一步：安装 BytecodeProvider 包装器
                installBytecodeProviderWrapper();
                
                // ✅ 第二步：编译所有 Mixin 并注册到包装器
                Map<String, byte[]> bytecodeCache = compileAllMixins();
                
                if (bytecodeCache.isEmpty()) {
                    RainJava.LOGGER.warn("No mixins were successfully compiled");
                    initialized = true;
                    return;
                }
                
                // 注册字节码到包装器
                for (Map.Entry<String, byte[]> entry : bytecodeCache.entrySet()) {
                    BytecodeProviderWrapper.registerMixin(entry.getKey(), entry.getValue());
                }
                
                // ✅ 第三步：创建并注册 MixinConfig
                registerMixinConfig(processor, bytecodeCache);
                
            } catch (Exception e) {
                RainJava.LOGGER.error("Failed to load dynamic mixins", e);
            }
            
            initialized = true;
        }
    }
    
    /**
     * ✅ 安装 BytecodeProvider 包装器
     */
    private void installBytecodeProviderWrapper() throws Exception {
        RainJava.LOGGER.info("Installing BytecodeProvider wrapper...");
        
        Class<?> serviceClass = Class.forName("org.spongepowered.rain.asm.service.MixinService");
        Object service = serviceClass.getMethod("getService").invoke(null);
        
        Object currentProvider = service.getClass()
            .getMethod("getBytecodeProvider")
            .invoke(service);
        
        RainJava.LOGGER.info("Current BytecodeProvider: {}", currentProvider.getClass().getName());
        
        // 如果已经是包装器，跳过
        if (currentProvider instanceof BytecodeProviderWrapper) {
            RainJava.LOGGER.info("BytecodeProvider already wrapped");
            return;
        }
        
        // 创建包装器
        BytecodeProviderWrapper wrapper = new BytecodeProviderWrapper(
            (org.spongepowered.rain.asm.service.IClassBytecodeProvider) currentProvider
        );
        
        // 替换 bytecodeProvider 字段
        boolean replaced = false;
        Class<?> current = service.getClass();
        
        while (current != null && current != Object.class) {
            try {
                java.lang.reflect.Field field = current.getDeclaredField("bytecodeProvider");
                field.setAccessible(true);
                field.set(service, wrapper);
                
                RainJava.LOGGER.info("✅ BytecodeProvider wrapped successfully!");
                replaced = true;
                break;
                
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        
        if (!replaced) {
            throw new RuntimeException("Could not find bytecodeProvider field");
        }
    }
    
    /**
     * ✅ 编译所有 Mixin
     */
    private Map<String, byte[]> compileAllMixins() {
        Map<String, byte[]> bytecodeCache = new HashMap<>();
        List<Path> mixinFiles = scanMixinSources();
        
        RainJava.LOGGER.info("Found {} mixin source files", mixinFiles.size());
        
        for (Path file : mixinFiles) {
            try {
                RainJava.LOGGER.info("Compiling: {}", file.getFileName());
                CompiledClass compiled = compiler.compile(file);
                bytecodeCache.put(compiled.className, compiled.bytecode);
                RainJava.LOGGER.info("✅ Compiled: {}", compiled.className);
            } catch (Exception e) {
                RainJava.LOGGER.error("Failed to compile: {}", file, e);
            }
        }
        
        return bytecodeCache;
    }
    
    /**
     * ✅ 注册 MixinConfig
     */
    private void registerMixinConfig(MixinProcessor processor, Map<String, byte[]> bytecodeCache) throws Exception {
        String configName = "dynamic_rainjava_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        RainJava.LOGGER.info("Creating config: {}", configName);
        
        MixinConfig config = MixinConfig.createDynamic(configName, "rainjava.mixins", 1000, false);
        
        for (String className : bytecodeCache.keySet()) {
            DefaultMixinConfigPlugin.registerDynamicMixin(className);
        }
        
        Extensions extensions = getExtensionsFromProcessor(processor);
        config.setExtensions(extensions);
        
        MixinServiceHelper.fixConfigService(config);
        
        try {
            //MixinConfigHelper.fullyInitializeConfig(config);
            RainJava.LOGGER.info("✅ Plugin initialized for config: {}", configName);
        } catch (Exception e) {
            RainJava.LOGGER.error("Failed to initialize plugin for config", e);
            throw e;
        }
        
        // 注册所有 Mixin
        int successCount = 0;
        for (Map.Entry<String, byte[]> entry : bytecodeCache.entrySet()) {
            try {
                MixinInfo mixinInfo = config.registerDynamicMixin(
                    entry.getKey(),
                    entry.getValue()
                );
                RainJava.LOGGER.info("✅ Registered: {}", entry.getKey());
                successCount++;
            } catch (Exception e) {
                RainJava.LOGGER.error("Failed to register: {}", entry.getKey(), e);
            }
        }
        
        if (successCount > 0) {
            config.prepare(extensions);
            config.postInitialise(extensions);
            addConfigToProcessor(processor, config);
            
            RainJava.LOGGER.info("========================================");
            RainJava.LOGGER.info("✅ Successfully loaded {} dynamic mixins", successCount);
            RainJava.LOGGER.info("========================================");
        } else {
            RainJava.LOGGER.warn("No mixins were successfully registered");
        }
    }
    
    /**
     * 扫描 mixins 目录
     */
    private List<Path> scanMixinSources() {
        List<Path> files = new ArrayList<>();
        
        try (java.util.stream.Stream<Path> paths = Files.walk(mixinsDir)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(files::add);
        } catch (Exception e) {
            RainJava.LOGGER.error("Failed to scan mixins directory", e);
        }
        
        return files;
    }
    
    /**
     * 从 MixinProcessor 获取 Extensions
     */
    private Extensions getExtensionsFromProcessor(MixinProcessor processor) {
        try {
            java.lang.reflect.Field field = processor.getClass().getDeclaredField("extensions");
            field.setAccessible(true);
            return (Extensions) field.get(processor);
        } catch (Exception e) {
            RainJava.LOGGER.error("Failed to get extensions", e);
            return null;
        }
    }
    
    /**
     * 将配置添加到 MixinProcessor
     */
    private void addConfigToProcessor(MixinProcessor processor, MixinConfig config) throws Exception {
        java.lang.reflect.Field configsField = processor.getClass().getDeclaredField("configs");
        configsField.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<MixinConfig> configs = (List<MixinConfig>) configsField.get(processor);
        
        for (MixinConfig existing : configs) {
            if (existing.getName().equals(config.getName())) {
                RainJava.LOGGER.warn("Config already exists: {}", config.getName());
                return;
            }
        }
        
        configs.add(config);
        configs.sort(MixinConfig::compareTo);
        
        RainJava.LOGGER.info("Added config to processor: {}", config.getName());
    }
}