package net.rain.rainjava.java.utils;

import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态 MCP 到 SRG 映射系统
 * 从 Forge 的映射文件中实时查找转换
 */
public class MinecraftHelper {
    public static final Logger LOGGER = LogManager.getLogger();
    public static final Map<String, Field> fieldCache = new ConcurrentHashMap<>();
    public static final Map<String, Method> methodCache = new ConcurrentHashMap<>();
    
    // 映射缓存: "ClassName.mcpName" -> "srgName"
    public static final Map<String, String> fieldMappings = new ConcurrentHashMap<>();
    public static final Map<String, String> methodMappings = new ConcurrentHashMap<>();
    
    // 映射文件资源路径
    public static final String MAPPING_RESOURCE_PATH = "/assets/mapping/map/mappings.tsrg";
    public static boolean mappingLoaded = false;
    public static Path mappingFilePath = null;
    
    /**
     * 标准化类名格式 - 将 $ 和 / 统一替换为 .
     * 这样可以匹配不同格式的类名：
     * - Item$Properties -> Item.Properties
     * - net/minecraft/world/item/Item -> net.minecraft.world.item.Item
     */
    public static String normalizeClassName(String className) {
        if (className == null) {
            return null;
        }
        return className.replace('$', '.').replace('/', '.');
    }
    
    /**
     * 从 JAR 资源中加载映射文件到内存 - 增强版
     * 尝试多种方式加载资源
     */
    public static void loadMappingsFromResource() {
        if (mappingLoaded) return;
        mappingLoaded = true;
        
        // 尝试多个可能的路径
        String[] possiblePaths = {
            "/assets/mappings/map/mappings.tsrg",
            "assets/mappings/map/mappings.tsrg",
            "/mappings.tsrg",
            "mappings.tsrg"
        };
        
        for (String path : possiblePaths) {
            LOGGER.debug("Trying to load mapping from: {}", path);
            
            // 方法1: 使用 Class.getResourceAsStream
            try (InputStream is = MinecraftHelper.class.getResourceAsStream(path)) {
                if (is != null) {
                    LOGGER.info("Successfully found resource using Class.getResourceAsStream: {}", path);
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                        parseMappingFile(reader);
                        LOGGER.info("Loaded {} field mappings and {} method mappings", 
                                   fieldMappings.size(), methodMappings.size());
                        return;
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to load from {} using Class.getResourceAsStream: {}", path, e.getMessage());
            }
            
            // 方法2: 使用 ClassLoader.getResourceAsStream
            try (InputStream is = MinecraftHelper.class.getClassLoader().getResourceAsStream(path.startsWith("/") ? path.substring(1) : path)) {
                if (is != null) {
                    LOGGER.info("Successfully found resource using ClassLoader.getResourceAsStream: {}", path);
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                        parseMappingFile(reader);
                        LOGGER.info("Loaded {} field mappings and {} method mappings", 
                                   fieldMappings.size(), methodMappings.size());
                        return;
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to load from {} using ClassLoader.getResourceAsStream: {}", path, e.getMessage());
            }
            
            // 方法3: 使用 Thread.currentThread().getContextClassLoader()
            try {
                ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
                if (contextClassLoader != null) {
                    try (InputStream is = contextClassLoader.getResourceAsStream(path.startsWith("/") ? path.substring(1) : path)) {
                        if (is != null) {
                            LOGGER.info("Successfully found resource using ContextClassLoader: {}", path);
                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                                parseMappingFile(reader);
                                LOGGER.info("Loaded {} field mappings and {} method mappings", 
                                           fieldMappings.size(), methodMappings.size());
                                return;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to load from {} using ContextClassLoader: {}", path, e.getMessage());
            }
        }
        
        LOGGER.warn("Mapping file not found in resources, will rely on ObfuscationReflectionHelper");
    }
    
    /**
     * 获取资源的输入流 - 提供多种加载方式
     */
    public static InputStream getResourceAsStream(String resourcePath) {
        // 方法1: Class.getResourceAsStream
        InputStream is = MinecraftHelper.class.getResourceAsStream(resourcePath);
        if (is != null) {
            LOGGER.debug("Found resource using Class.getResourceAsStream: {}", resourcePath);
            return is;
        }
        
        // 方法2: ClassLoader.getResourceAsStream (移除开头的 /)
        String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        is = MinecraftHelper.class.getClassLoader().getResourceAsStream(path);
        if (is != null) {
            LOGGER.debug("Found resource using ClassLoader.getResourceAsStream: {}", path);
            return is;
        }
        
        // 方法3: ContextClassLoader
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null) {
            is = contextClassLoader.getResourceAsStream(path);
            if (is != null) {
                LOGGER.debug("Found resource using ContextClassLoader: {}", path);
                return is;
            }
        }
        
        LOGGER.warn("Resource not found: {}", resourcePath);
        return null;
    }
    
    /**
     * 检查资源是否存在
     */
    public static boolean resourceExists(String resourcePath) {
        try (InputStream is = getResourceAsStream(resourcePath)) {
            return is != null;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 查找映射文件路径(在文件系统中)
     */
    public static void findMappingFile() {
        if (mappingFilePath != null) return;
        
        try {
            // 尝试从常见位置查找映射文件
            String[] possiblePaths = {
                "config/forge/mappings.tsrg",
                "mappings/mappings.tsrg",
                ".gradle/caches/forge_gradle/mcp_mappings/mappings.tsrg"
            };
            
            for (String pathStr : possiblePaths) {
                Path path = Paths.get(pathStr);
                if (Files.exists(path)) {
                    mappingFilePath = path;
                    LOGGER.info("Found mapping file at: {}", path);
                    return;
                }
            }
            
            LOGGER.debug("Mapping file not found in file system");
        } catch (Exception e) {
            LOGGER.debug("Error finding mapping file: {}", e.getMessage());
        }
    }
    
    /**
     * 解析映射文件并缓存到内存 - 修复版
     */
    public static void parseMappingFile(BufferedReader reader) throws Exception {
        String line;
        String currentClass = null;
        boolean isFirstLine = true;
        int lineNumber = 0;
        
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            String originalLine = line;
            line = line.trim();
            
            if (line.isEmpty() || line.startsWith("#")) continue;
            
            // 跳过 TSRG 头部行
            if (isFirstLine && line.startsWith("tsrg2")) {
                LOGGER.debug("Found TSRG2 header: {}", line);
                isFirstLine = false;
                continue;
            }
            isFirstLine = false;
            
            // TSRG2 格式:
            // SRG类名 MCP类名                                    (0个制表符)
            // \tMCP字段名 SRG字段名                              (1个制表符)
            // \tMCP方法名 方法签名 SRG方法名                     (1个制表符)
            // \t\tstatic/参数索引 参数MCP名 参数SRG名             (2个制表符)
            
            // 计算制表符数量来判断缩进级别
            int tabCount = 0;
            for (char c : originalLine.toCharArray()) {
                if (c == '\t') tabCount++;
                else break;
            }
            
            if (tabCount == 0) {
                // 这是类定义行
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    // parts[0] = SRG类名, parts[1] = MCP类名
                    // 使用 normalizeClassName 统一格式
                    currentClass = normalizeClassName(parts[1]);
                    LOGGER.debug("Line {}: Processing class: {} (SRG: {})", lineNumber, currentClass, parts[0]);
                }
            } else if (currentClass != null && tabCount == 1) {
                // 一个制表符: 字段或方法定义
                String[] parts = line.split("\\s+");
                
                if (parts.length >= 2) {
                    String mcpName = parts[0];  // 第一个是 MCP 名称
                    
                    // 判断是字段还是方法
                    // 字段格式: mcpName srgName
                    // 方法格式: mcpName (descriptor) srgName
                    
                    if (parts[1].startsWith("(")) {
                        // 这是方法 (有描述符)
                        if (parts.length >= 3) {
                            String srgName = parts[2];  // SRG 名称在第三个位置
                            String key = currentClass + "." + mcpName;
                            
                            if (srgName.startsWith("m_") || srgName.equals("<init>") || srgName.equals("<clinit>")) {
                                methodMappings.put(key, srgName);
                                LOGGER.debug("Line {}: Mapped method: {} -> {}", lineNumber, key, srgName);
                            }
                        }
                    } else {
                        // 这是字段 (没有描述符)
                        String srgName = parts[1];  // SRG 名称在第二个位置
                        String key = currentClass + "." + mcpName;
                        
                        if (srgName.startsWith("f_")) {
                            fieldMappings.put(key, srgName);
                            LOGGER.debug("Line {}: Mapped field: {} -> {}", lineNumber, key, srgName);
                        }
                    }
                }
            }
            // tabCount >= 2 的是方法参数/修饰符,我们不需要处理这些
        }
        
        LOGGER.info("Finished parsing. Total field mappings: {}, method mappings: {}", 
                   fieldMappings.size(), methodMappings.size());
    }
    
    /**
     * 从映射文件中查找字段的 SRG 名称
     * 支持 TSRG 格式 (Forge 1.20.1 使用的格式)
     */
    public static String findSrgFieldName(String className, String mcpName) {
        // 标准化类名格式
        className = normalizeClassName(className);
        
        String key = className + "." + mcpName;
        
        // 检查缓存
        if (fieldMappings.containsKey(key)) {
            return fieldMappings.get(key);
        }
        
        // 如果还没加载资源映射,先尝试加载
        if (!mappingLoaded) {
            loadMappingsFromResource();
            // 再次检查缓存
            if (fieldMappings.containsKey(key)) {
                return fieldMappings.get(key);
            }
        }
        
        // 如果资源加载失败,尝试从文件系统加载
        findMappingFile();
        if (mappingFilePath != null && Files.exists(mappingFilePath)) {
            try (BufferedReader reader = new BufferedReader(new FileReader(mappingFilePath.toFile()))) {
                String line;
                boolean inTargetClass = false;
                
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    
                    if (!line.startsWith("\t") && !line.startsWith("    ")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            String mappedClassName = normalizeClassName(parts[1]);
                            inTargetClass = mappedClassName.equals(className);
                        }
                    } else if (inTargetClass) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2) {
                            String mappedName = parts[0];  // MCP名称
                            String srgName = parts[1];     // SRG名称
                            
                            if (srgName.startsWith("f_") && mappedName.equals(mcpName)) {
                                fieldMappings.put(key, srgName);
                                LOGGER.debug("Found mapping: {}.{} -> {}", className, mcpName, srgName);
                                return srgName;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Error reading mapping file for {}.{}: {}", className, mcpName, e.getMessage());
            }
        }
        
        return null;
    }
    
    /**
     * 从映射文件中查找方法的 SRG 名称
     */
    public static String findSrgMethodName(String className, String mcpName) {
        // 标准化类名格式
        className = normalizeClassName(className);
        
        String key = className + "." + mcpName;
        
        if (methodMappings.containsKey(key)) {
            return methodMappings.get(key);
        }
        
        if (!mappingLoaded) {
            loadMappingsFromResource();
            if (methodMappings.containsKey(key)) {
                return methodMappings.get(key);
            }
        }
        
        findMappingFile();
        if (mappingFilePath != null && Files.exists(mappingFilePath)) {
            try (BufferedReader reader = new BufferedReader(new FileReader(mappingFilePath.toFile()))) {
                String line;
                String currentClass = null;
                
                while ((line = reader.readLine()) != null) {
                    String originalLine = line;
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    
                    // 计算制表符数量
                    int tabCount = 0;
                    for (char c : originalLine.toCharArray()) {
                        if (c == '\t') tabCount++;
                        else break;
                    }
                    
                    if (tabCount == 0) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            String mappedClassName = normalizeClassName(parts[1]);
                            currentClass = mappedClassName;
                        }
                    } else if (currentClass != null && currentClass.equals(className) && tabCount == 1) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            String mappedName = parts[0];  // MCP名称
                            
                            // 判断是方法还是字段
                            if (parts[1].startsWith("(")) {
                                // 这是方法,SRG名称在第三个位置
                                if (parts.length >= 3) {
                                    String srgName = parts[2];
                                    if ((srgName.startsWith("m_") || srgName.equals("<init>") || srgName.equals("<clinit>")) 
                                        && mappedName.equals(mcpName)) {
                                        methodMappings.put(key, srgName);
                                        LOGGER.debug("Found method mapping: {}.{} -> {}", className, mcpName, srgName);
                                        return srgName;
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Error reading mapping file for method {}.{}: {}", className, mcpName, e.getMessage());
            }
        }
        
        return null;
    }
    
    /**
     * 获取静态字段值 - 自动 MCP 到 SRG 转换
     * 增强版:更智能的字段查找策略
     */
    @SuppressWarnings("unchecked")
    public static <T> T getStaticField(Class<?> clazz, String mcpName) {
        String cacheKey = clazz.getName() + "." + mcpName;
        
        Field field = fieldCache.computeIfAbsent(cacheKey, k -> {
            // 策略1: 尝试直接访问 (开发环境或未混淆)
            try {
                Field f = clazz.getDeclaredField(mcpName);
                f.setAccessible(true);
                LOGGER.debug("Found field directly: {}.{}", clazz.getName(), mcpName);
                return f;
            } catch (NoSuchFieldException ignored) {}
            
            // 策略2: 使用 ObfuscationReflectionHelper (Forge 官方推荐)
            try {
                Field f = ObfuscationReflectionHelper.findField(clazz, mcpName);
                f.setAccessible(true);
                LOGGER.debug("Found field via ObfuscationReflectionHelper: {}.{}", clazz.getName(), mcpName);
                return f;
            } catch (Exception e) {
                LOGGER.debug("ObfuscationReflectionHelper failed for {}.{}: {}", clazz.getName(), mcpName, e.getMessage());
            }
            
            // 策略3: 从映射文件查找 SRG 名称
            String srgName = findSrgFieldName(clazz.getName(), mcpName);
            if (srgName != null) {
                try {
                    Field f = clazz.getDeclaredField(srgName);
                    f.setAccessible(true);
                    LOGGER.debug("Found field via mapping: {}.{} -> {}", clazz.getName(), mcpName, srgName);
                    return f;
                } catch (NoSuchFieldException e) {
                    LOGGER.debug("Mapped SRG name {} not found in class", srgName);
                }
            }
            
            // 策略4: 遍历所有字段尝试匹配 (包括父类)
            Class<?> currentClass = clazz;
            while (currentClass != null && currentClass != Object.class) {
                for (Field f : currentClass.getDeclaredFields()) {
                    if (f.getName().equals(mcpName) || 
                        (srgName != null && f.getName().equals(srgName))) {
                        f.setAccessible(true);
                        LOGGER.debug("Found field by iteration: {}.{}", clazz.getName(), f.getName());
                        return f;
                    }
                }
                currentClass = currentClass.getSuperclass();
            }
            
            // 策略5: 尝试查找所有以 f_ 开头的字段 (可能是混淆后的)
            LOGGER.warn("Failed to find field {}. Available fields in {}:", mcpName, clazz.getName());
            for (Field f : clazz.getDeclaredFields()) {
                LOGGER.warn("  - {} (type: {})", f.getName(), f.getType().getSimpleName());
            }
            
            throw new RuntimeException("Field not found: " + mcpName + " in " + clazz.getName() + 
                                     (srgName != null ? " (tried SRG: " + srgName + ")" : ""));
        });
        
        try {
            return (T) field.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get static field: " + mcpName, e);
        }
    }
    
    /**
     * 设置静态字段值
     */
    public static void setStaticField(Class<?> clazz, String mcpName, Object value) {
        String cacheKey = clazz.getName() + "." + mcpName;
        
        Field field = fieldCache.computeIfAbsent(cacheKey, k -> {
            try {
                Field f = clazz.getDeclaredField(mcpName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
            
            try {
                Field f = ObfuscationReflectionHelper.findField(clazz, mcpName);
                f.setAccessible(true);
                return f;
            } catch (Exception ignored) {}
            
            String srgName = findSrgFieldName(clazz.getName(), mcpName);
            if (srgName != null) {
                try {
                    Field f = clazz.getDeclaredField(srgName);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {}
            }
            
            throw new RuntimeException("Field not found: " + mcpName + " in " + clazz.getName());
        });
        
        try {
            field.set(null, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set static field: " + mcpName, e);
        }
    }
    
    /**
     * 获取实例字段值
     */
    @SuppressWarnings("unchecked")
    public static <T> T getField(Object obj, String mcpName) {
        if (obj == null) throw new IllegalArgumentException("Object cannot be null");
        
        Class<?> clazz = obj.getClass();
        String cacheKey = clazz.getName() + "." + mcpName;
        
        Field field = fieldCache.computeIfAbsent(cacheKey, k -> {
            try {
                Field f = clazz.getDeclaredField(mcpName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
            
            try {
                Field f = ObfuscationReflectionHelper.findField(clazz, mcpName);
                f.setAccessible(true);
                return f;
            } catch (Exception ignored) {}
            
            String srgName = findSrgFieldName(clazz.getName(), mcpName);
            if (srgName != null) {
                try {
                    Field f = clazz.getDeclaredField(srgName);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {}
            }
            
            throw new RuntimeException("Field not found: " + mcpName + " in " + clazz.getName());
        });
        
        try {
            return (T) field.get(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get field: " + mcpName, e);
        }
    }
    
    /**
     * 设置实例字段值
     */
    public static void setField(Object obj, String mcpName, Object value) {
        if (obj == null) throw new IllegalArgumentException("Object cannot be null");
        
        Class<?> clazz = obj.getClass();
        String cacheKey = clazz.getName() + "." + mcpName;
        
        Field field = fieldCache.computeIfAbsent(cacheKey, k -> {
            try {
                Field f = clazz.getDeclaredField(mcpName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
            
            try {
                Field f = ObfuscationReflectionHelper.findField(clazz, mcpName);
                f.setAccessible(true);
                return f;
            } catch (Exception ignored) {}
            
            String srgName = findSrgFieldName(clazz.getName(), mcpName);
            if (srgName != null) {
                try {
                    Field f = clazz.getDeclaredField(srgName);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {}
            }
            
            throw new RuntimeException("Field not found: " + mcpName + " in " + clazz.getName());
        });
        
        try {
            field.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + mcpName, e);
        }
    }
    
    /**
     * 调用静态方法
     */
    @SuppressWarnings("unchecked")
    public static <T> T invokeStaticMethod(Class<?> clazz, String mcpName, Object... args) {
        String cacheKey = clazz.getName() + "." + mcpName;
        
        Method method = methodCache.computeIfAbsent(cacheKey, k -> {
            Class<?>[] paramTypes = getParameterTypes(args);
            
            try {
                Method m = clazz.getDeclaredMethod(mcpName, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            
            String srgName = findSrgMethodName(clazz.getName(), mcpName);
            if (srgName != null) {
                try {
                    Method m = clazz.getDeclaredMethod(srgName, paramTypes);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {}
            }
            
            // 模糊匹配
            for (Method m : clazz.getDeclaredMethods()) {
                if ((m.getName().equals(mcpName) || (srgName != null && m.getName().equals(srgName))) 
                    && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return m;
                }
            }
            
            throw new RuntimeException("Method not found: " + mcpName + " in " + clazz.getName());
        });
        
        try {
            return (T) method.invoke(null, args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke static method: " + mcpName, e);
        }
    }
    
    /**
     * 调用实例方法
     */
    @SuppressWarnings("unchecked")
    public static <T> T invokeMethod(Object obj, String mcpName, Object... args) {
        if (obj == null) throw new IllegalArgumentException("Object cannot be null");
        
        Class<?> clazz = obj.getClass();
        String cacheKey = clazz.getName() + "." + mcpName;
        
        Method method = methodCache.computeIfAbsent(cacheKey, k -> {
            Class<?>[] paramTypes = getParameterTypes(args);
            
            try {
                Method m = clazz.getDeclaredMethod(mcpName, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            
            String srgName = findSrgMethodName(clazz.getName(), mcpName);
            if (srgName != null) {
                try {
                    Method m = clazz.getDeclaredMethod(srgName, paramTypes);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {}
            }
            
            for (Method m : clazz.getDeclaredMethods()) {
                if ((m.getName().equals(mcpName) || (srgName != null && m.getName().equals(srgName))) 
                    && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return m;
                }
            }
            
            throw new RuntimeException("Method not found: " + mcpName + " in " + clazz.getName());
        });
        
        try {
            return (T) method.invoke(obj, args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke method: " + mcpName, e);
        }
    }
    
    public static Class<?>[] getParameterTypes(Object... args) {
        if (args == null || args.length == 0) return new Class<?>[0];
        
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] != null ? args[i].getClass() : Object.class;
        }
        return types;
    }
    
    /**
     * 清除所有缓存
     */
    public static void clearCache() {
        fieldCache.clear();
        methodCache.clear();
        fieldMappings.clear();
        methodMappings.clear();
        LOGGER.info("MinecraftHelper cache cleared");
    }
    
    /**
     * 通过类名字符串获取静态字段
     */
    @SuppressWarnings("unchecked")
    public static <T> T getStaticField(String className, String fieldName) {
        try {
            Class<?> clazz = Class.forName(className);
            return getStaticField(clazz, fieldName);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Class not found: " + className, e);
        }
    }
    
    /**
     * 通过类名字符串调用静态方法
     */
    @SuppressWarnings("unchecked")
    public static <T> T invokeStaticMethod(String className, String methodName, Object... args) {
        try {
            Class<?> clazz = Class.forName(className);
            return invokeStaticMethod(clazz, methodName, args);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Class not found: " + className, e);
        }
    }
}

/**
 * 简短别名
 */
class MC extends MinecraftHelper {
}