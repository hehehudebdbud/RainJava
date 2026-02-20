package net.rain.rainjava.mixin;

import org.apache.logging.log4j.Logger;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.jar.*;

/**
 * Mixin 调试辅助工具
 * 在 JAR 构建后立即验证包结构是否正确
 */
public class MixinDebugHelper {
    
    /**
     * 详细验证 Mixin JAR 和配置是否匹配
     */
    public static boolean validateMixinSetup(Path jarFile, Path configFile, Logger logger) {
        logger.info("========================================");
        logger.info("MIXIN SETUP VALIDATION");
        logger.info("========================================");
        
        boolean hasErrors = false;
        
        try {
            // 1. 读取配置文件
            String configContent = Files.readString(configFile);
            com.google.gson.JsonObject config = new com.google.gson.Gson()
                .fromJson(configContent, com.google.gson.JsonObject.class);
            
            String packageName = config.has("package") ? config.get("package").getAsString() : "";
            logger.info("1. Config package: {}", packageName.isEmpty() ? "(root)" : packageName);
            
            // 收集所有声明的 mixin 类
            List<String> declaredMixins = new ArrayList<>();
            if (config.has("mixins")) {
                config.getAsJsonArray("mixins").forEach(e -> declaredMixins.add(e.getAsString()));
            }
            if (config.has("client")) {
                config.getAsJsonArray("client").forEach(e -> declaredMixins.add(e.getAsString()));
            }
            if (config.has("server")) {
                config.getAsJsonArray("server").forEach(e -> declaredMixins.add(e.getAsString()));
            }
            
            logger.info("2. Declared mixin classes: {}", declaredMixins.size());
            declaredMixins.forEach(m -> logger.info("   - {}", m));
            
            // 2. 检查 JAR 文件
            if (!Files.exists(jarFile)) {
                logger.error("✗ JAR file does not exist: {}", jarFile);
                return false;
            }
            
            logger.info("3. JAR file exists: {} ({} bytes)", jarFile, Files.size(jarFile));
            
            // 3. 验证 JAR 内容
            try (JarFile jar = new JarFile(jarFile.toFile())) {
                // 检查配置文件
                JarEntry configEntry = jar.getJarEntry("rainjava.mixins.json");
                if (configEntry == null) {
                    logger.error("✗ CRITICAL: rainjava.mixins.json NOT in JAR root!");
                    hasErrors = true;
                } else {
                    logger.info("4. ✓ Config file in JAR root");
                }
                
                // 检查每个声明的 mixin 类
                logger.info("5. Verifying each mixin class...");
                for (String mixinClass : declaredMixins) {
                    // 构建完整类名
                    String fullClassName = packageName.isEmpty() ? 
                        mixinClass : packageName + "." + mixinClass;
                    
                    // 转换为 JAR 路径
                    String jarPath = fullClassName.replace('.', '/') + ".class";
                    
                    JarEntry classEntry = jar.getJarEntry(jarPath);
                    
                    if (classEntry == null) {
                        logger.error("   ✗ NOT FOUND: {} (package: {}, looking for: {})", 
                            mixinClass, packageName, jarPath);
                        hasErrors = true;
                        
                        // 尝试查找类文件在哪里
                        logger.error("     Searching for this class in JAR...");
                        boolean found = false;
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            JarEntry entry = entries.nextElement();
                            String name = entry.getName();
                            if (name.endsWith(mixinClass + ".class")) {
                                logger.error("     Found at: {} (WRONG LOCATION!)", name);
                                logger.error("     Expected: {}", jarPath);
                                found = true;
                            }
                        }
                        if (!found) {
                            logger.error("     Class file not in JAR at all!");
                        }
                    } else {
                        logger.info("   ✓ FOUND: {} -> {}", mixinClass, jarPath);
                    }
                }
                
                // 列出 JAR 中所有的 class 文件
                logger.info("6. All class files in JAR:");
                Enumeration<JarEntry> entries = jar.entries();
                int classCount = 0;
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".class")) {
                        logger.info("   - {}", entry.getName());
                        classCount++;
                    }
                }
                
                if (classCount == 0) {
                    logger.error("✗ CRITICAL: No class files in JAR!");
                    hasErrors = true;
                }
            }
            
        } catch (Exception e) {
            logger.error("Validation failed with exception", e);
            hasErrors = true;
        }
        
        logger.info("========================================");
        if (hasErrors) {
            logger.error("✗ VALIDATION FAILED - Mixins will NOT work!");
            logger.error("========================================");
            return false;
        } else {
            logger.info("✓ VALIDATION PASSED - Setup looks correct");
            logger.info("========================================");
            return true;
        }
    }
    
    /**
     * 显示修复建议
     */
    public static void showFixSuggestions(Path classesDir, Path configFile, Logger logger) {
        logger.info("========================================");
        logger.info("FIX SUGGESTIONS");
        logger.info("========================================");
        
        try {
            // 读取配置
            String configContent = Files.readString(configFile);
            com.google.gson.JsonObject config = new com.google.gson.Gson()
                .fromJson(configContent, com.google.gson.JsonObject.class);
            String packageName = config.has("package") ? config.get("package").getAsString() : "";
            
            logger.info("Config declares package: {}", packageName.isEmpty() ? "(root)" : packageName);
            
            // 扫描实际的 class 文件
            logger.info("Scanning actual class files in: {}", classesDir);
            
            if (!Files.exists(classesDir)) {
                logger.error("Classes directory does not exist!");
                return;
            }
            
            Files.walk(classesDir)
                .filter(p -> p.toString().endsWith(".class"))
                .forEach(classFile -> {
                    Path relative = classesDir.relativize(classFile);
                    String actualPath = relative.toString().replace('\\', '/');
                    String actualPackage = actualPath.replace(".class", "").replace('/', '.');
                    
                    logger.info("  Found class: {}", actualPath);
                    logger.info("    Full name: {}", actualPackage);
                    
                    // 检查是否匹配配置的包
                    if (!packageName.isEmpty() && !actualPackage.startsWith(packageName)) {
                        logger.warn("    ⚠ MISMATCH! This doesn't start with '{}'", packageName);
                        logger.warn("    The class should be at: {}", 
                            packageName.replace('.', '/') + "/" + classFile.getFileName());
                    }
                });
            
            logger.info("========================================");
            logger.info("RECOMMENDATIONS:");
            logger.info("1. Make sure your .java source files have correct 'package' declarations");
            logger.info("2. The package in source must match the directory structure");
            logger.info("3. Example: if package is 'rainjava.startup.mixins', file should be at:");
            logger.info("   RainJava/startup/mixins/ItemStackMixin.java");
            logger.info("4. After fixing, delete .mixin folder and restart");
            logger.info("========================================");
            
        } catch (Exception e) {
            logger.error("Failed to show suggestions", e);
        }
    }
}
