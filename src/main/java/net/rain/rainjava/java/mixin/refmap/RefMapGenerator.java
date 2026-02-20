package net.rain.rainjava.mixin.refmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RefMap 生成器 - 基于源代码解析
 */
public class RefMapGenerator {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Logger logger;
    
    // 存储映射数据: obfName -> srgName
    private final Map<String, String> classMap = new HashMap<>();
    private final Map<String, String> methodMap = new HashMap<>();
    private final Map<String, String> fieldMap = new HashMap<>();
    
    // 每个 Mixin 的映射数据
    private final Map<String, Map<String, String>> mixinMappings = new LinkedHashMap<>();
    
    private Path mappingFilePath = null;
    private boolean mappingLoaded = false;
    
    private static final String[] DEFAULT_MAPPING_PATHS = {
        "config/forge/mappings.tsrg",
        "mappings/mappings.tsrg",
        ".gradle/caches/forge_gradle/mcp_mappings/mappings.tsrg",
        "joined.tsrg",
        "mappings/joined.tsrg"
    };
    
    private static final String[] DEFAULT_RESOURCE_PATHS = {
        "/assets/mappings/map/mappings.tsrg",
        "assets/mappings/map/mappings.tsrg",
        "/mappings.tsrg",
        "mappings.tsrg"
    };
    
    public RefMapGenerator(Logger logger) {
        this.logger = logger;
    }
    
    public void loadMappings(Path mappingFile) {
        if (mappingLoaded) {
            logger.info("Mappings already loaded");
            return;
        }
        
        loadMappingsFromResource();
        
        if (mappingLoaded) return;
        
        findAndLoadMappings();
        
        if (!mappingLoaded) {
            logger.warn("Failed to load mappings from any source. RefMap generation may be incomplete.");
        }
    }
    
    private void loadMappingsFromResource() {
        for (String resourcePath : DEFAULT_RESOURCE_PATHS) {
            logger.info("Trying to load mapping from resource: {}", resourcePath);
            
            try (InputStream is = getResourceAsStream(resourcePath)) {
                if (is != null) {
                    logger.info("Found mapping resource: {}", resourcePath);
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                        parseTSRG2(reader);
                        mappingLoaded = true;
                        logger.info("✓ Loaded mappings from resource: {}", resourcePath);
                        logger.info("  Classes: {}, Methods: {}, Fields: {}", 
                                  classMap.size(), methodMap.size(), fieldMap.size());
                        return;
                    }
                }
            } catch (Exception e) {
                logger.info("Failed to load from resource {}: {}", resourcePath, e.getMessage());
            }
        }
    }
    
    private void loadMappingsFromFile(Path mappingFile) {
        if (!Files.exists(mappingFile)) {
            logger.info("Mapping file not found: {}", mappingFile);
            return;
        }
        
        try (BufferedReader reader = Files.newBufferedReader(mappingFile)) {
            logger.info("Loading mappings from file: {}", mappingFile);
            parseTSRG2(reader);
            mappingFilePath = mappingFile;
            mappingLoaded = true;
            logger.info("✓ Loaded mappings from file: {}", mappingFile);
            logger.info("  Classes: {}, Methods: {}, Fields: {}", 
                      classMap.size(), methodMap.size(), fieldMap.size());
        } catch (IOException e) {
            logger.error("Failed to load mappings from file: {}", mappingFile, e);
        }
    }
    
    private void findAndLoadMappings() {
        for (String pathStr : DEFAULT_MAPPING_PATHS) {
            Path path = Paths.get(pathStr);
            if (Files.exists(path)) {
                loadMappingsFromFile(path);
                if (mappingLoaded) return;
            }
        }
        
        try {
            Files.list(Paths.get("."))
                 .filter(p -> p.toString().endsWith(".tsrg"))
                 .findFirst()
                 .ifPresent(this::loadMappingsFromFile);
        } catch (IOException e) {
            logger.info("Error searching for .tsrg files: {}", e.getMessage());
        }
    }
    
    private InputStream getResourceAsStream(String resourcePath) {
        InputStream is = RefMapGenerator.class.getResourceAsStream(resourcePath);
        if (is != null) return is;
        
        String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        is = RefMapGenerator.class.getClassLoader().getResourceAsStream(path);
        if (is != null) return is;
        
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null) {
            is = contextClassLoader.getResourceAsStream(path);
            if (is != null) return is;
        }
        
        return null;
    }
    
    private void parseTSRG2(BufferedReader reader) throws IOException {
        classMap.clear();
        methodMap.clear();
        fieldMap.clear();
        
        String line;
        String currentClassObf = null;
        String currentClassSrg = null;
        boolean isFirstLine = true;
        
        while ((line = reader.readLine()) != null) {
            String originalLine = line;
            line = line.trim();
            
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            
            if (isFirstLine && line.startsWith("tsrg2")) {
                logger.info("Found TSRG2 header: {}", line);
                isFirstLine = false;
                continue;
            }
            isFirstLine = false;
            
            int tabCount = 0;
            for (char c : originalLine.toCharArray()) {
                if (c == '\t') tabCount++;
                else break;
            }
            
            if (tabCount == 0) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    currentClassObf = parts[0];
                    currentClassSrg = parts[1];
                    classMap.put(currentClassObf, currentClassSrg);
                }
            } else if (currentClassObf != null && currentClassSrg != null && tabCount == 1) {
                parseMemberMapping(line, currentClassObf, currentClassSrg);
            }
        }
    }
    
    private void parseMemberMapping(String line, String classObf, String classSrg) {
        String[] parts = line.split("\\s+");
        
        if (parts.length < 2) {
            return;
        }
        
        String nameObf = parts[0];
        String descriptor = parts[1];
        
        if (descriptor.startsWith("(")) {
            // 方法
            if (parts.length >= 3) {
                String nameSrg = parts[2];
                String key = classObf + "." + nameObf + descriptor;
                String value = convertToInternalName(classSrg) + "." + nameSrg + descriptor;
                methodMap.put(key, value);
            }
        } else {
            // 字段
            String nameSrg = descriptor;
            String key = classObf + "." + nameObf;
            String value = nameSrg;
            fieldMap.put(key, value);
        }
    }
    
    private String convertToInternalName(String className) {
        return className.replace('.', '/');
    }
    
    private String convertToJvmType(String internalName) {
        if (internalName.startsWith("L") && internalName.endsWith(";")) {
            return internalName;
        }
        return "L" + internalName + ";";
    }
    
    /**
     * 分析 Mixin - 使用源代码和目标类信息
     */
    public void analyzeMixinWithSource(String mixinName, Path sourceFile, Path classFile, String targetClass) {
        logger.info("Analyzing mixin: {}", mixinName);
        logger.info("  Target class: {}", targetClass);
        
        try {
            // 读取源代码
            String sourceCode = Files.readString(sourceFile);
            
            // 读取字节码
            byte[] bytecode = Files.readAllBytes(classFile);
            ClassReader reader = new ClassReader(bytecode);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);
            
            Map<String, String> mappings = new HashMap<>();
            
            // 分析字段引用
            int fieldCount = analyzeMixinFieldsFromSource(sourceCode, classNode, targetClass, mappings);
            logger.info("  Found {} field references", fieldCount);
            
            // 分析方法引用
            int methodCount = analyzeMixinMethodsFromSource(sourceCode, classNode, targetClass, mappings);
            logger.info("  Found {} method references", methodCount);
            
            if (!mappings.isEmpty()) {
                mixinMappings.put(mixinName, mappings);
                logger.info("  ✓ Extracted {} total references", mappings.size());
                
                // 输出示例映射
                if (!mappings.isEmpty()) {
                    Map.Entry<String, String> example = mappings.entrySet().iterator().next();
                    logger.info("    Example: {} -> {}", example.getKey(), example.getValue());
                }
            } else {
                logger.info("  ℹ No obfuscated references found (may use deobfuscated names)");
            }
            
        } catch (IOException e) {
            logger.error("Failed to analyze mixin: {}", mixinName, e);
        }
    }
    
    /**
     * 从源代码分析字段引用
     */
    private int analyzeMixinFieldsFromSource(String sourceCode, ClassNode classNode, 
                                              String targetClass, Map<String, String> mappings) {
        int count = 0;
        
        // 查找所有 @Shadow 字段
        Pattern shadowPattern = Pattern.compile("@Shadow[^;]*\\s+(private|public|protected)?\\s+[\\w<>\\[\\]]+\\s+(\\w+)\\s*;");
        Matcher matcher = shadowPattern.matcher(sourceCode);
        
        while (matcher.find()) {
            String fieldName = matcher.group(2);
            logger.debug("    Found @Shadow field in source: {}", fieldName);
            
            // 在字节码中查找对应的字段以获取描述符
            for (FieldNode field : classNode.fields) {
                if (field.name.equals(fieldName)) {
                    String mapped = findFieldMapping(targetClass, fieldName);
                    if (mapped != null) {
                        String value = mapped + ":" + field.desc;
                        mappings.put(fieldName, value);
                        
                        String fullKey = convertToJvmType(targetClass) + fieldName;
                        mappings.put(fullKey, value);
                        
                        logger.debug("      Mapped: {} -> {}", fieldName, mapped);
                        count++;
                    } else {
                        logger.debug("      No mapping found for: {}", fieldName);
                    }
                    break;
                }
            }
        }
        
        return count;
    }
    
    /**
     * 从源代码分析方法引用
     */
    private int analyzeMixinMethodsFromSource(String sourceCode, ClassNode classNode,
                                               String targetClass, Map<String, String> mappings) {
        int count = 0;
        
        // 查找 @Inject 等注解中的 method 参数
        Pattern injectPattern = Pattern.compile("@(?:Inject|Redirect|ModifyVariable|ModifyArg)\\s*\\([^)]*method\\s*=\\s*\"([^\"]+)\"");
        Matcher matcher = injectPattern.matcher(sourceCode);
        
        while (matcher.find()) {
            String methodReference = matcher.group(1);
            logger.debug("    Found method reference: {}", methodReference);
            
            if (parseMethodReference(methodReference, targetClass, mappings)) {
                count++;
            }
        }
        
        // 查找 @Overwrite 方法
        for (MethodNode method : classNode.methods) {
            if (method.visibleAnnotations != null) {
                for (AnnotationNode anno : method.visibleAnnotations) {
                    if (anno.desc != null && anno.desc.contains("Overwrite")) {
                        logger.debug("    Found @Overwrite method: {}", method.name);
                        
                        String mapped = findMethodMapping(targetClass, method.name, method.desc);
                        if (mapped != null) {
                            mappings.put(method.name, mapped);
                            
                            String fullKey = convertToJvmType(targetClass) + method.name + method.desc;
                            mappings.put(fullKey, mapped);
                            
                            logger.debug("      Mapped: {} -> {}", method.name, mapped);
                            count++;
                        }
                    }
                }
            }
        }
        
        return count;
    }
    
    /**
     * 解析方法引用字符串
     */
    private boolean parseMethodReference(String reference, String targetClass, Map<String, String> mappings) {
        // 格式 1: "methodName(II)V"
        Pattern simplePattern = Pattern.compile("([^(]+)(\\([^)]*\\).+)");
        Matcher simpleMatcher = simplePattern.matcher(reference);
        
        if (simpleMatcher.find()) {
            String methodName = simpleMatcher.group(1);
            String descriptor = simpleMatcher.group(2);
            
            String mapped = findMethodMapping(targetClass, methodName, descriptor);
            if (mapped != null) {
                mappings.put(methodName, mapped);
                
                String fullKey = convertToJvmType(targetClass) + methodName + descriptor;
                mappings.put(fullKey, mapped);
                
                logger.debug("      Mapped method: {} -> {}", methodName, mapped);
                return true;
            } else {
                logger.debug("      No mapping found for: {}{}", methodName, descriptor);
            }
        }
        
        // 格式 2: "Lcom/example/Class;methodName(II)V"
        Pattern fullPattern = Pattern.compile("L([^;]+);([^(]+)(\\([^)]*\\).+)");
        Matcher fullMatcher = fullPattern.matcher(reference);
        
        if (fullMatcher.find()) {
            String className = fullMatcher.group(1);
            String methodName = fullMatcher.group(2);
            String descriptor = fullMatcher.group(3);
            
            String mapped = findMethodMapping(className, methodName, descriptor);
            if (mapped != null) {
                mappings.put(reference, mapped);
                mappings.put(methodName, mapped);
                logger.debug("      Mapped method: {} -> {}", methodName, mapped);
                return true;
            }
        }
        
        return false;
    }
    
    private String findMethodMapping(String owner, String name, String desc) {
        String dotOwner = owner.replace('/', '.');
        String key = dotOwner + "." + name + desc;
        return methodMap.get(key);
    }
    
    private String findFieldMapping(String owner, String name) {
        String dotOwner = owner.replace('/', '.');
        String key = dotOwner + "." + name;
        return fieldMap.get(key);
    }
    
    /**
     * 生成 RefMap JSON 文件
     */
    public void generateRefMap(Path outputPath, String refmapName) {
        if (!mappingLoaded) {
            logger.warn("Mappings not loaded. Attempting to load automatically...");
            loadMappings(null);
        }
        
        logger.info("Generating RefMap: {}", refmapName);
        
        JsonObject root = new JsonObject();
        JsonObject mappings = new JsonObject();
        
        for (Map.Entry<String, Map<String, String>> entry : mixinMappings.entrySet()) {
            String mixinName = entry.getKey();
            Map<String, String> mixinMap = entry.getValue();
            
            if (!mixinMap.isEmpty()) {
                JsonObject mixinObj = new JsonObject();
                for (Map.Entry<String, String> mapping : mixinMap.entrySet()) {
                    mixinObj.addProperty(mapping.getKey(), mapping.getValue());
                }
                mappings.add(mixinName, mixinObj);
            }
        }
        
        root.add("mappings", mappings);
        
        JsonObject data = new JsonObject();
        JsonObject searge = new JsonObject();
        
        for (Map.Entry<String, Map<String, String>> entry : mixinMappings.entrySet()) {
            String mixinName = entry.getKey();
            Map<String, String> mixinMap = entry.getValue();
            
            if (!mixinMap.isEmpty()) {
                JsonObject mixinObj = new JsonObject();
                for (Map.Entry<String, String> mapping : mixinMap.entrySet()) {
                    mixinObj.addProperty(mapping.getKey(), mapping.getValue());
                }
                searge.add(mixinName, mixinObj);
            }
        }
        
        data.add("searge", searge);
        root.add("data", data);
        
        try {
            String json = GSON.toJson(root);
            Files.writeString(outputPath, json);
            
            logger.info("✓ RefMap generated successfully:");
            logger.info("  File: {}", outputPath);
            logger.info("  Mixins: {}", mixinMappings.size());
            
            int totalMappings = mixinMappings.values().stream().mapToInt(Map::size).sum();
            logger.info("  Total mappings: {}", totalMappings);
            
            if (!mixinMappings.isEmpty()) {
                String firstMixin = mixinMappings.keySet().iterator().next();
                Map<String, String> firstMap = mixinMappings.get(firstMixin);
                if (!firstMap.isEmpty()) {
                    logger.info("Example mapping for {}:", firstMixin);
                    Map.Entry<String, String> example = firstMap.entrySet().iterator().next();
                    logger.info("  {} -> {}", example.getKey(), example.getValue());
                }
            }
            
        } catch (IOException e) {
            logger.error("Failed to write RefMap", e);
        }
    }
    
    public Map<String, Integer> getStatistics() {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("classes", classMap.size());
        stats.put("methods", methodMap.size());
        stats.put("fields", fieldMap.size());
        stats.put("mixins", mixinMappings.size());
        stats.put("totalMappings", 
            mixinMappings.values().stream().mapToInt(Map::size).sum());
        return stats;
    }
    
    public boolean isMappingLoaded() {
        return mappingLoaded;
    }
    
    public Path getMappingFilePath() {
        return mappingFilePath;
    }
    
    public void clear() {
        classMap.clear();
        methodMap.clear();
        fieldMap.clear();
        mixinMappings.clear();
        mappingFilePath = null;
        mappingLoaded = false;
    }
    
    public void reloadMappings(Path mappingFile) {
        clear();
        loadMappings(mappingFile);
    }
}