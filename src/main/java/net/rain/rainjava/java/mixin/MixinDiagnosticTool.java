package net.rain.rainjava.mixin;

import org.apache.logging.log4j.Logger;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.jar.*;

/**
 * Mixin诊断工具
 * 帮助诊断为什么Mixin配置无法加载
 */
public class MixinDiagnosticTool {
    
    public static void diagnose(Path gameDir, Logger logger) {
        logger.info("========================================");
        logger.info("MIXIN DIAGNOSTIC TOOL");
        logger.info("========================================");
        
        Path mixinDir = gameDir.resolve(".mixin");
        Path configDir = mixinDir.resolve("mixins_config");
        Path classesDir = mixinDir.resolve("mixins").resolve("classes");
        Path configFile = configDir.resolve("rainjava.mixins.json");
        Path jarFile = configDir.resolve("rainjava.mixins.jar");
        
        // 1. 检查目录结构
        logger.info("1. Checking directory structure...");
        checkPath(mixinDir, "Mixin base directory", logger);
        checkPath(configDir, "Config directory", logger);
        checkPath(classesDir, "Classes directory", logger);
        checkPath(configFile, "Config file", logger);
        checkPath(jarFile, "JAR file", logger);
        
        // 2. 检查配置文件内容
        if (Files.exists(configFile)) {
            logger.info("\n2. Analyzing config file...");
            analyzeConfigFile(configFile, logger);
        }
        
        // 3. 检查class文件
        if (Files.exists(classesDir)) {
            logger.info("\n3. Checking compiled classes...");
            checkClassFiles(classesDir, logger);
        }
        
        // 4. 检查JAR文件
        if (Files.exists(jarFile)) {
            logger.info("\n4. Analyzing JAR file...");
            analyzeJarFile(jarFile, logger);
        }
        
        // 5. 检查classpath
        logger.info("\n5. Checking classpath...");
        checkClasspath(logger);
        
        // 6. 测试配置访问
        logger.info("\n6. Testing config accessibility...");
        testConfigAccess(logger);
        
        // 7. 给出建议
        logger.info("\n7. Recommendations:");
        giveRecommendations(gameDir, logger);
        
        logger.info("========================================");
        logger.info("DIAGNOSTIC COMPLETE");
        logger.info("========================================");
    }
    
    private static void checkPath(Path path, String description, Logger logger) {
        boolean exists = Files.exists(path);
        String type = Files.isDirectory(path) ? "Directory" : "File";
        
        if (exists) {
            try {
                if (Files.isRegularFile(path)) {
                    long size = Files.size(path);
                    logger.info("  ✓ {} exists: {} ({} bytes)", description, path, size);
                } else {
                    logger.info("  ✓ {} exists: {}", description, path);
                }
            } catch (IOException e) {
                logger.warn("  ⚠ {} exists but cannot read: {}", description, path);
            }
        } else {
            logger.warn("  ✗ {} does NOT exist: {}", description, path);
        }
    }
    
    private static void analyzeConfigFile(Path configFile, Logger logger) {
        try {
            String content = Files.readString(configFile);
            logger.info("  Config file size: {} bytes", content.length());
            logger.info("  Config content:");
            
            // 解析JSON
            com.google.gson.JsonObject json = new com.google.gson.Gson()
                .fromJson(content, com.google.gson.JsonObject.class);
            
            logger.info("    package: {}", json.has("package") ? json.get("package").getAsString() : "(none)");
            logger.info("    required: {}", json.has("required") ? json.get("required").getAsBoolean() : false);
            logger.info("    minVersion: {}", json.has("minVersion") ? json.get("minVersion").getAsString() : "(none)");
            logger.info("    compatibilityLevel: {}", json.has("compatibilityLevel") ? json.get("compatibilityLevel").getAsString() : "(none)");
            
            if (json.has("mixins")) {
                logger.info("    mixins (common): {}", json.getAsJsonArray("mixins"));
            }
            if (json.has("client")) {
                logger.info("    client: {}", json.getAsJsonArray("client"));
            }
            if (json.has("server")) {
                logger.info("    server: {}", json.getAsJsonArray("server"));
            }
            
        } catch (Exception e) {
            logger.error("  ✗ Failed to analyze config file", e);
        }
    }
    
    private static void checkClassFiles(Path classesDir, Logger logger) {
        try {
            List<Path> classFiles = new ArrayList<>();
            Files.walk(classesDir)
                .filter(p -> p.toString().endsWith(".class"))
                .forEach(classFiles::add);
            
            logger.info("  Found {} class file(s):", classFiles.size());
            
            for (Path classFile : classFiles) {
                Path relative = classesDir.relativize(classFile);
                String className = relative.toString()
                    .replace(File.separatorChar, '/')
                    .replace(".class", "");
                long size = Files.size(classFile);
                
                logger.info("    ✓ {} ({} bytes)", className, size);
            }
            
            if (classFiles.isEmpty()) {
                logger.warn("  ✗ No class files found! Mixins need to be compiled first.");
            }
            
        } catch (IOException e) {
            logger.error("  ✗ Failed to check class files", e);
        }
    }
    
    private static void analyzeJarFile(Path jarFile, Logger logger) {
        try {
            long jarSize = Files.size(jarFile);
            logger.info("  JAR file size: {} bytes", jarSize);
            
            if (jarSize == 0) {
                logger.error("  ✗ JAR file is EMPTY!");
                return;
            }
            
            try (JarFile jar = new JarFile(jarFile.toFile())) {
                // 检查MANIFEST
                Manifest manifest = jar.getManifest();
                if (manifest != null) {
                    logger.info("  ✓ MANIFEST.MF found:");
                    manifest.getMainAttributes().forEach((key, value) -> {
                        logger.info("      {}: {}", key, value);
                    });
                } else {
                    logger.warn("  ⚠ No MANIFEST.MF in JAR");
                }
                
                // 检查条目
                boolean hasConfig = false;
                int classCount = 0;
                List<String> entries = new ArrayList<>();
                
                Enumeration<JarEntry> jarEntries = jar.entries();
                while (jarEntries.hasMoreElements()) {
                    JarEntry entry = jarEntries.nextElement();
                    String name = entry.getName();
                    entries.add(name);
                    
                    if (name.equals("rainjava.mixins.json")) {
                        hasConfig = true;
                        
                        // 读取配置
                        try (InputStream is = jar.getInputStream(entry)) {
                            String configContent = new String(is.readAllBytes());
                            logger.info("  ✓ Config file found in JAR:");
                            logger.info("      Size: {} bytes", configContent.length());
                            logger.info("      Preview: {}", 
                                configContent.substring(0, Math.min(100, configContent.length())) + "...");
                        }
                    } else if (name.endsWith(".class")) {
                        classCount++;
                    }
                }
                
                logger.info("  JAR summary:");
                logger.info("    Total entries: {}", entries.size());
                logger.info("    Config present: {}", hasConfig);
                logger.info("    Class files: {}", classCount);
                
                if (!hasConfig) {
                    logger.error("  ✗ CRITICAL: rainjava.mixins.json NOT in JAR!");
                    logger.info("  Available entries:");
                    entries.forEach(e -> logger.info("      {}", e));
                }
                
                if (classCount == 0) {
                    logger.error("  ✗ CRITICAL: No .class files in JAR!");
                }
            }
            
        } catch (IOException e) {
            logger.error("  ✗ Failed to analyze JAR file", e);
        }
    }
    
    private static void checkClasspath(Logger logger) {
        String classpath = System.getProperty("java.class.path");
        logger.info("  System classpath entries:");
        
        if (classpath != null) {
            String[] entries = classpath.split(File.pathSeparator);
            for (String entry : entries) {
                if (entry.contains("rainjava") || entry.contains("mixin")) {
                    logger.info("    ✓ {}", entry);
                }
            }
        }
        
        // 检查ClassLoader
        ClassLoader[] loaders = {
            Thread.currentThread().getContextClassLoader(),
            ClassLoader.getSystemClassLoader(),
            MixinDiagnosticTool.class.getClassLoader()
        };
        
        logger.info("  Available ClassLoaders:");
        for (int i = 0; i < loaders.length; i++) {
            if (loaders[i] != null) {
                logger.info("    {}: {}", i, loaders[i].getClass().getName());
            }
        }
    }
    
    private static void testConfigAccess(Logger logger) {
        String resourceName = "rainjava.mixins.json";
        
        ClassLoader[] loaders = {
            Thread.currentThread().getContextClassLoader(),
            ClassLoader.getSystemClassLoader(),
            MixinDiagnosticTool.class.getClassLoader()
        };
        
        boolean found = false;
        
        for (int i = 0; i < loaders.length; i++) {
            if (loaders[i] == null) continue;
            
            java.net.URL url = loaders[i].getResource(resourceName);
            
            if (url != null) {
                logger.info("  ✓ Found via ClassLoader #{}: {}", i, url);
                
                // 尝试读取
                try (InputStream is = url.openStream()) {
                    byte[] content = is.readAllBytes();
                    logger.info("    Content accessible: {} bytes", content.length);
                    found = true;
                } catch (IOException e) {
                    logger.warn("    ⚠ Cannot read content: {}", e.getMessage());
                }
            } else {
                logger.info("  ✗ Not found via ClassLoader #{}", i);
            }
        }
        
        if (!found) {
            logger.error("  ✗ CRITICAL: Config file NOT accessible from any ClassLoader!");
            logger.error("  This means the JAR is not properly loaded into classpath.");
        }
    }
    
    private static void giveRecommendations(Path gameDir, Logger logger) {
        Path jarFile = gameDir.resolve(".mixin/mixins_config/rainjava.mixins.jar");
        Path configFile = gameDir.resolve(".mixin/mixins_config/rainjava.mixins.json");
        Path classesDir = gameDir.resolve(".mixin/mixins/classes");
        
        List<String> issues = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        
        // 检查常见问题
        if (!Files.exists(jarFile)) {
            issues.add("JAR file does not exist");
            recommendations.add("Run the game once to generate Mixin JAR from source files");
        } else {
            try {
                if (Files.size(jarFile) == 0) {
                    issues.add("JAR file is empty");
                    recommendations.add("Delete .mixin folder and regenerate");
                }
            } catch (IOException ignored) {}
        }
        
        if (!Files.exists(configFile)) {
            issues.add("Config file does not exist");
            recommendations.add("Ensure MixinManager.generateMixinConfig() is called during startup");
        }
        
        if (!Files.exists(classesDir)) {
            issues.add("Classes directory does not exist");
            recommendations.add("Ensure MixinManager.compileMixins() is called before generating config");
        }
        
        // 检查classpath
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        java.net.URL configUrl = cl.getResource("rainjava.mixins.json");
        if (configUrl == null) {
            issues.add("Config not in classpath");
            recommendations.add("JAR needs to be added to classpath BEFORE Mixin initialization");
            recommendations.add("Current workaround: Restart game after first generation");
        }
        
        if (issues.isEmpty()) {
            logger.info("  ✓ No obvious issues detected!");
            logger.info("  If Mixins still don't work, check:");
            logger.info("    - Mixin class has @Mixin annotation");
            logger.info("    - Target class name is correct");
            logger.info("    - Injection points are valid");
        } else {
            logger.warn("  Issues detected: {}", issues.size());
            issues.forEach(issue -> logger.warn("    • {}", issue));
            
            logger.info("  Recommendations:");
            recommendations.forEach(rec -> logger.info("    → {}", rec));
        }
        
        logger.info("\n  Quick fix steps:");
        logger.info("    1. Stop the game");
        logger.info("    2. Delete: {}", gameDir.resolve(".mixin"));
        logger.info("    3. Start the game (generates JAR)");
        logger.info("    4. Restart the game (loads Mixins)");
    }
}
