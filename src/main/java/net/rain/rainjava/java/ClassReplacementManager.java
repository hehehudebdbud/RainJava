package net.rain.rainjava.java;

import net.rain.rainjava.RainJava;
import net.rain.rainjava.utils.PathUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * 类替换管理器
 * 负责扫描 replace 文件夹并编译替换类
 */
public class ClassReplacementManager {
    private final JavaSourceCompiler compiler;
    private final Path replaceDirectory;
    private final Path replacementOutputPath;
    private final Map<String, byte[]> compiledReplacements = new HashMap<>();
    
    public ClassReplacementManager(JavaSourceCompiler compiler, Path baseDirectory) {
        this.compiler = compiler;
        this.replaceDirectory = baseDirectory.resolve("replace");
        
        // 使用 rainjava_replacements 目录存储编译后的类
        Path gameDir = Paths.get(".").toAbsolutePath().normalize();
        this.replacementOutputPath = gameDir.resolve(".rainjava_replacements");
        
        // 确保 replace 目录存在
        if (!Files.exists(replaceDirectory)) {
            try {
                Files.createDirectories(replaceDirectory);
                RainJava.LOGGER.info("Created replace directory: {}", replaceDirectory);
            } catch (Exception e) {
                RainJava.LOGGER.error("Failed to create replace directory", e);
            }
        }
        
        // 确保输出目录存在
        if (!Files.exists(replacementOutputPath)) {
            try {
                Files.createDirectories(replacementOutputPath);
                RainJava.LOGGER.info("Created rainjava_replacements directory: {}", replacementOutputPath);
            } catch (Exception e) {
                RainJava.LOGGER.error("Failed to create rainjava_replacements directory", e);
            }
        }
    }
    
    /**
     * 扫描并编译所有替换类
     */
    public void processReplacements() {
        if (compiler == null) {
            RainJava.LOGGER.warn("Compiler not available, cannot process class replacements");
            return;
        }
        
        if (!Files.exists(replaceDirectory)) {
            RainJava.LOGGER.debug("Replace directory does not exist: {}", replaceDirectory);
            return;
        }
        
        RainJava.LOGGER.info("========================================");
        RainJava.LOGGER.info("Processing Class Replacements");
        RainJava.LOGGER.info("========================================");
        
        List<Path> javaFiles = scanJavaFiles();
        
        if (javaFiles.isEmpty()) {
            RainJava.LOGGER.info("No replacement files found in {}", replaceDirectory);
            RainJava.LOGGER.info("========================================");
            return;
        }
        
        RainJava.LOGGER.info("Found {} replacement file(s)", javaFiles.size());
        
        // 编译所有替换文件
        for (Path file : javaFiles) {
            compileReplacement(file);
        }
        
        // 应用替换到转换系统
        if (!compiledReplacements.isEmpty()) {
            applyReplacements();
        }
        
        RainJava.LOGGER.info("========================================");
        RainJava.LOGGER.info("Class Replacement Complete: {} classes compiled", compiledReplacements.size());
        RainJava.LOGGER.info("========================================");
    }
    
    /**
     * 扫描所有 Java 文件
     */
    private List<Path> scanJavaFiles() {
        List<Path> javaFiles = new ArrayList<>();
        
        try (Stream<Path> paths = Files.walk(replaceDirectory)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                 .forEach(javaFiles::add);
        } catch (Exception e) {
            RainJava.LOGGER.error("Failed to scan replace directory: {}", replaceDirectory, e);
        }
        
        return javaFiles;
    }
    
    /**
     * 编译单个替换文件
     */
    private void compileReplacement(Path file) {
        try {
            RainJava.LOGGER.info("Compiling replacement: {}", file.getFileName());
            
            long startTime = System.currentTimeMillis();
            
            Path realFile = PathUtils.removeRainJavaPrefix(file);
            
            // 编译文件
            CompiledClass compiled = compiler.compile(realFile);
            
            long compileTime = System.currentTimeMillis() - startTime;
            
            // 转换类名为内部格式 (用 / 分隔)
            String internalClassName = compiled.className.replace('.', '/');
            
            // 保存编译结果
            compiledReplacements.put(internalClassName, compiled.bytecode);
            
            RainJava.LOGGER.info("✓ Compiled: {} -> {} ({} bytes, {}ms)", 
                file.getFileName(), internalClassName, compiled.bytecode.length, compileTime);
            
        } catch (Exception e) {
            RainJava.LOGGER.error("✗ Failed to compile: {}", file, e);
        }
    }
    
    /**
     * 应用替换到转换系统
     * 将编译后的类写入 rainjava_replacements 目录
     */
    private void applyReplacements() {
        try {
            int successCount = 0;
            
            // 将编译后的类写入输出目录
            for (Map.Entry<String, byte[]> entry : compiledReplacements.entrySet()) {
                String className = entry.getKey();
                byte[] bytecode = entry.getValue();
                
                // 构建文件路径
                String relativePath = className.replace("/", File.separator);
                Path classFilePath = replacementOutputPath.resolve(relativePath + ".class");
                
                // 创建父目录
                Path parentDir = classFilePath.getParent();
                if (parentDir != null && !Files.exists(parentDir)) {
                    Files.createDirectories(parentDir);
                }
                
                // 写入字节码
                Files.write(classFilePath, bytecode);
                
                RainJava.LOGGER.info("✓ Wrote replacement: {}", classFilePath);
                successCount++;
            }
            
            RainJava.LOGGER.info("Successfully wrote {} replacement(s) to transformation directory", successCount);
            RainJava.LOGGER.info("Replacements will be applied on next game start");
            
        } catch (Exception e) {
            RainJava.LOGGER.error("Failed to write replacements to transformation directory", e);
        }
    }
    
    /**
     * 获取已编译的替换类
     */
    public Map<String, byte[]> getCompiledReplacements() {
        return new HashMap<>(compiledReplacements);
    }
    
    /**
     * 检查是否有替换
     */
    public boolean hasReplacements() {
        return !compiledReplacements.isEmpty();
    }
    
    /**
     * 获取替换的类数量
     */
    public int getReplacementCount() {
        return compiledReplacements.size();
    }
    
    /**
     * 获取输出路径
     */
    public Path getReplacementOutputPath() {
        return replacementOutputPath;
    }
}
