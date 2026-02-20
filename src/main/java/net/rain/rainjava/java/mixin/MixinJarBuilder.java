package net.rain.rainjava.mixin;

import java.io.*;
import java.nio.file.*;
import java.util.jar.*;
import org.apache.logging.log4j.Logger;

/**
 * Mixin JAR 构建器 - 完全修复版
 * 确保包结构与配置文件完全匹配
 */
public class MixinJarBuilder {
    
    public static void buildMixinJar(Path classesDir, Path configFile, Path outputJar, Logger logger) throws IOException {
        logger.info("========================================");
        logger.info("Building Mixin JAR: {}", outputJar.getFileName());
        logger.info("========================================");
        logger.info("Source directories:");
        logger.info("  Classes: {}", classesDir);
        logger.info("  Config: {}", configFile);
        logger.info("  Output: {}", outputJar);
        
        // 验证输入
        if (!Files.exists(classesDir)) {
            throw new IOException("Classes directory does not exist: " + classesDir);
        }
        
        if (!Files.exists(configFile)) {
            throw new IOException("Config file does not exist: " + configFile);
        }
        
        // 读取配置文件以获取包名
        String configPackage = extractPackageFromConfig(configFile, logger);
        
        // 确保输出目录存在
        Files.createDirectories(outputJar.getParent());
        
        // 删除旧JAR
        if (Files.exists(outputJar)) {
            Files.delete(outputJar);
            logger.info("Deleted old JAR file");
        }
        
        int classCount = 0;
        int totalSize = 0;
        
        try (JarOutputStream jos = new JarOutputStream(
                Files.newOutputStream(outputJar), 
                createManifest())) {
            
            logger.info("Creating JAR with manifest...");
            
            // 1. 首先添加配置文件到JAR根目录(非常重要!)
            String configName = configFile.getFileName().toString();
            logger.info("Adding config file: {}", configName);
            
            JarEntry configEntry = new JarEntry(configName);
            configEntry.setTime(System.currentTimeMillis());
            jos.putNextEntry(configEntry);
            
            byte[] configBytes = Files.readAllBytes(configFile);
            jos.write(configBytes);
            jos.closeEntry();
            
            logger.info("  ✓ Added config: {} ({} bytes)", configName, configBytes.length);
            
            // 2. 验证并添加所有class文件
            logger.info("Scanning for class files...");
            ClassFileResult result = addClassFiles(jos, classesDir, classesDir, configPackage, logger);
            classCount = result.count;
            totalSize = result.totalSize;
            
            if (classCount == 0) {
                throw new IOException("No class files were added to JAR! Check class file paths.");
            }
            
            jos.finish();
        }
        
        // 验证生成的JAR
        long jarSize = Files.size(outputJar);
        
        logger.info("========================================");
        logger.info("✓ Mixin JAR created successfully");
        logger.info("  File: {}", outputJar.getFileName());
        logger.info("  Size: {} bytes", jarSize);
        logger.info("  Classes: {}", classCount);
        logger.info("  Total class bytes: {}", totalSize);
        logger.info("========================================");
        
        // 额外验证:读取JAR内容
        verifyJarContents(outputJar, configPackage, logger);
    }
    
    /**
     * 从配置文件中提取包名
     */
    private static String extractPackageFromConfig(Path configFile, Logger logger) {
        try {
            String content = Files.readString(configFile);
            com.google.gson.JsonObject json = new com.google.gson.Gson()
                .fromJson(content, com.google.gson.JsonObject.class);
            
            if (json.has("package")) {
                String pkg = json.get("package").getAsString();
                logger.info("Config package: {}", pkg.isEmpty() ? "(root)" : pkg);
                return pkg;
            }
        } catch (Exception e) {
            logger.warn("Could not extract package from config", e);
        }
        return "";
    }
    
    /**
     * 创建MANIFEST.MF
     */
    private static Manifest createManifest() {
        Manifest manifest = new Manifest();
        Attributes mainAttrs = manifest.getMainAttributes();
        
        // 必需属性
        mainAttrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        
        // Mixin配置属性(重要!)
        mainAttrs.putValue("MixinConfigs", "rainjava.mixins.json");
        
        // 可选:添加更多元数据
        mainAttrs.putValue("Created-By", "RainJava Mixin System");
        mainAttrs.putValue("Built-Date", String.valueOf(System.currentTimeMillis()));
        
        return manifest;
    }
    
    /**
     * 递归添加所有class文件 - 带包结构验证
     */
    private static ClassFileResult addClassFiles(JarOutputStream jos, Path rootDir, 
                                                 Path currentDir, String expectedPackage, 
                                                 Logger logger) throws IOException {
        int count = 0;
        int totalSize = 0;
        
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(currentDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    // 递归处理子目录
                    ClassFileResult subResult = addClassFiles(jos, rootDir, entry, expectedPackage, logger);
                    count += subResult.count;
                    totalSize += subResult.totalSize;
                    
                } else if (entry.toString().endsWith(".class")) {
                    // 计算相对路径(使用正斜杠,这是JAR标准)
                    Path relativePath = rootDir.relativize(entry);
                    String entryName = relativePath.toString().replace('\\', '/');
                    
                    // 验证包结构
                    String className = entryName.replace(".class", "").replace('/', '.');
                    if (!expectedPackage.isEmpty() && !className.startsWith(expectedPackage)) {
                        logger.warn("  ⚠ Class package mismatch: {} (expected to start with {})", 
                            className, expectedPackage);
                        logger.warn("    This may cause Mixin to fail loading the class!");
                    }
                    
                    // 读取class文件
                    byte[] classBytes = Files.readAllBytes(entry);
                    
                    // 添加到JAR
                    JarEntry jarEntry = new JarEntry(entryName);
                    jarEntry.setTime(Files.getLastModifiedTime(entry).toMillis());
                    jos.putNextEntry(jarEntry);
                    jos.write(classBytes);
                    jos.closeEntry();
                    
                    logger.info("  ✓ Added: {} ({} bytes)", entryName, classBytes.length);
                    
                    count++;
                    totalSize += classBytes.length;
                }
            }
        }
        
        return new ClassFileResult(count, totalSize);
    }
    
    /**
     * 验证JAR内容 - 增强版
     */
    private static void verifyJarContents(Path jarPath, String expectedPackage, Logger logger) {
        logger.info("Verifying JAR contents...");
        
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            // 检查MANIFEST
            Manifest manifest = jarFile.getManifest();
            if (manifest != null) {
                String mixinConfigs = manifest.getMainAttributes().getValue("MixinConfigs");
                logger.info("  MANIFEST.MF:");
                logger.info("    Manifest-Version: {}", 
                    manifest.getMainAttributes().getValue("Manifest-Version"));
                logger.info("    MixinConfigs: {}", mixinConfigs);
                
                if (mixinConfigs == null || !mixinConfigs.equals("rainjava.mixins.json")) {
                    logger.error("  ✗ CRITICAL: MixinConfigs not set correctly!");
                }
            } else {
                logger.warn("  ⚠ No MANIFEST.MF found in JAR");
            }
            
            // 列出所有条目
            logger.info("  JAR entries:");
            boolean hasConfig = false;
            int classCount = 0;
            java.util.List<String> mixinClasses = new java.util.ArrayList<>();
            
            java.util.Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                long size = entry.getSize();
                
                if (name.equals("rainjava.mixins.json")) {
                    hasConfig = true;
                    logger.info("    ✓ {} ({} bytes) - CONFIG FILE", name, size);
                    
                    // 读取并验证配置内容
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        byte[] content = is.readAllBytes();
                        String configStr = new String(content);
                        
                        // 解析配置
                        com.google.gson.JsonObject config = new com.google.gson.Gson()
                            .fromJson(configStr, com.google.gson.JsonObject.class);
                        
                        logger.info("      Config package: {}", 
                            config.has("package") ? config.get("package").getAsString() : "(none)");
                        
                        // 收集所有声明的mixin类
                        if (config.has("mixins")) {
                            config.getAsJsonArray("mixins").forEach(e -> 
                                mixinClasses.add(e.getAsString()));
                        }
                        if (config.has("client")) {
                            config.getAsJsonArray("client").forEach(e -> 
                                mixinClasses.add(e.getAsString()));
                        }
                        if (config.has("server")) {
                            config.getAsJsonArray("server").forEach(e -> 
                                mixinClasses.add(e.getAsString()));
                        }
                        
                        logger.info("      Declared mixins: {}", mixinClasses);
                    }
                    
                } else if (name.endsWith(".class")) {
                    classCount++;
                    logger.info("    ✓ {} ({} bytes)", name, size);
                }
            }
            
            logger.info("  Summary:");
            logger.info("    Config file present: {}", hasConfig);
            logger.info("    Class files: {}", classCount);
            
            // 验证关键问题
            boolean hasErrors = false;
            
            if (!hasConfig) {
                logger.error("  ✗ CRITICAL: Config file 'rainjava.mixins.json' not found in JAR!");
                hasErrors = true;
            }
            
            if (classCount == 0) {
                logger.error("  ✗ CRITICAL: No class files found in JAR!");
                hasErrors = true;
            }
            
            // 验证配置中声明的每个mixin类是否都存在
            if (hasConfig && !mixinClasses.isEmpty()) {
                logger.info("  Verifying mixin classes...");
                for (String mixinClass : mixinClasses) {
                    String fullClassName = expectedPackage.isEmpty() ? 
                        mixinClass : expectedPackage + "." + mixinClass;
                    String classPath = fullClassName.replace('.', '/') + ".class";
                    
                    JarEntry classEntry = jarFile.getJarEntry(classPath);
                    if (classEntry == null) {
                        logger.error("    ✗ Mixin class NOT FOUND: {} (looked for: {})", 
                            fullClassName, classPath);
                        hasErrors = true;
                    } else {
                        logger.info("    ✓ Mixin class found: {}", fullClassName);
                    }
                }
            }
            
            if (!hasErrors) {
                logger.info("  ✓ JAR verification PASSED");
            } else {
                logger.error("  ✗ JAR verification FAILED - Mixins will not load!");
            }
            
        } catch (IOException e) {
            logger.error("Failed to verify JAR contents", e);
        }
    }
    
    /**
     * 辅助类:存储class文件统计信息
     */
    private static class ClassFileResult {
        final int count;
        final int totalSize;
        
        ClassFileResult(int count, int totalSize) {
            this.count = count;
            this.totalSize = totalSize;
        }
    }
}
