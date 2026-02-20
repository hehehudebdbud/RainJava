package net.rain.rainjava.mixin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.rain.rainjava.RainJava;
import net.rain.rainjava.mixin.refmap.*;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 修复版 Mixin 管理器 - 使用源代码解析
 */
public class MixinManager {
    private static final Logger LOGGER = RainJava.LOGGER;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String MIXIN_PACKAGE = "rainjava.mixins";
    private static final String DYNAMIC_MIXIN_CONFIG = "rainjava.mixins.json";
    private static final String REFMAP_NAME = "rainjava.refmap.json";
    private static final String COMPILE_STATE_FILE = "compile_state.json";

    private final Path mixinSourceDir;
    private final Path mixinOutputDir;
    private final Path mixinConfigDir;
    private final Path mixinConfigFile;
    private final Path refmapFile;
    private final Path compileStateFile;
    private final Path mappingFile;

    private final Map<String, MixinInfo> discoveredMixins = new LinkedHashMap<>();
    private final RefMapGenerator refMapGenerator;
    private boolean mixinsCompiled = false;

    public MixinManager(Path gameDir) {
        this.mixinSourceDir = gameDir.resolve("RainJava/mixins");
        this.mixinConfigDir = gameDir.resolve(".rain_mixin");
        this.mixinOutputDir = mixinConfigDir.resolve("rainjava/mixins");
        this.mixinConfigFile = mixinConfigDir.resolve(DYNAMIC_MIXIN_CONFIG);
        this.refmapFile = mixinConfigDir.resolve(REFMAP_NAME);
        this.compileStateFile = mixinConfigDir.resolve(COMPILE_STATE_FILE);
        this.mappingFile = null;
        this.refMapGenerator = new RefMapGenerator(LOGGER);

        try {
            Files.createDirectories(mixinSourceDir);
            Files.createDirectories(mixinOutputDir);
            LOGGER.info("========================================");
            LOGGER.info("RainMixin Manager Initialized");
            LOGGER.info("========================================");
            LOGGER.info("Source Dir:  {}", mixinSourceDir);
            LOGGER.info("Output Dir:  {}", mixinOutputDir);
            LOGGER.info("Config File: {}", mixinConfigFile);
            LOGGER.info("RefMap File: {}", refmapFile);
            LOGGER.info("Package:     {}", MIXIN_PACKAGE);
            LOGGER.info("========================================");
        } catch (IOException e) {
            LOGGER.error("Failed to create mixin directories", e);
        }
    }

    /**
     * 核心方法：检测文件是否有变动
     */
    public boolean needsRecompile() {
        LOGGER.info("Checking if recompilation is needed...");

        if (!Files.exists(mixinSourceDir)) {
            LOGGER.info("Source directory does not exist, no compilation needed");
            return false;
        }

        Map<String, FileSnapshot> currentFiles = scanCurrentFiles();
        
        if (currentFiles.isEmpty()) {
            LOGGER.info("No mixin source files found");
            if (Files.exists(mixinConfigFile) || Files.exists(refmapFile)) {
                LOGGER.info("No source files but compiled artifacts exist, will clean up");
                return true;
            }
            return false;
        }

        CompileState lastState = loadCompileState();
        
        if (lastState == null || lastState.files == null || lastState.files.isEmpty()) {
            LOGGER.info("No previous compile state found (first run), compilation needed");
            return true;
        }

        if (currentFiles.size() != lastState.files.size()) {
            LOGGER.info("File count changed: {} -> {}", 
                lastState.files.size(), currentFiles.size());
            return true;
        }

        for (Map.Entry<String, FileSnapshot> entry : currentFiles.entrySet()) {
            String path = entry.getKey();
            FileSnapshot currentSnapshot = entry.getValue();
            FileSnapshot lastSnapshot = lastState.files.get(path);

            if (lastSnapshot == null) {
                LOGGER.info("New file detected: {}", path);
                return true;
            }

            if (currentSnapshot.lastModified != lastSnapshot.lastModified) {
                LOGGER.info("File modified (timestamp changed): {}", path);
                LOGGER.info("  Old: {}, New: {}", lastSnapshot.lastModified, currentSnapshot.lastModified);
                return true;
            }

            if (currentSnapshot.size != lastSnapshot.size) {
                LOGGER.info("File modified (size changed): {}", path);
                LOGGER.info("  Old: {} bytes, New: {} bytes", lastSnapshot.size, currentSnapshot.size);
                return true;
            }
        }

        for (String oldPath : lastState.files.keySet()) {
            if (!currentFiles.containsKey(oldPath)) {
                LOGGER.info("File deleted: {}", oldPath);
                return true;
            }
        }

        LOGGER.info("✓ No changes detected, compilation not needed");
        return false;
    }

    private Map<String, FileSnapshot> scanCurrentFiles() {
        Map<String, FileSnapshot> snapshots = new HashMap<>();
        
        try {
            if (!Files.exists(mixinSourceDir)) {
                return snapshots;
            }

            Files.walk(mixinSourceDir)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        FileSnapshot snapshot = new FileSnapshot();
                        snapshot.path = p.toString();
                        snapshot.lastModified = Files.getLastModifiedTime(p).toMillis();
                        snapshot.size = Files.size(p);
                        snapshots.put(snapshot.path, snapshot);
                        
                        LOGGER.debug("Scanned file: {} (modified: {}, size: {} bytes)", 
                            p.getFileName(), snapshot.lastModified, snapshot.size);
                    } catch (IOException e) {
                        LOGGER.warn("Failed to scan file: {}", p, e);
                    }
                });
        } catch (IOException e) {
            LOGGER.error("Failed to scan mixin source directory", e);
        }

        return snapshots;
    }

    private CompileState loadCompileState() {
        if (!Files.exists(compileStateFile)) {
            LOGGER.debug("No compile state file found at: {}", compileStateFile);
            return null;
        }

        try {
            String content = Files.readString(compileStateFile);
            CompileState state = GSON.fromJson(content, CompileState.class);
            LOGGER.debug("Loaded compile state from: {}", compileStateFile);
            LOGGER.debug("  Compile time: {}", state.compileTime);
            LOGGER.debug("  Tracked files: {}", state.files != null ? state.files.size() : 0);
            return state;
        } catch (Exception e) {
            LOGGER.warn("Failed to load compile state, will treat as first run", e);
            return null;
        }
    }

    private void saveCompileState() {
        try {
            Map<String, FileSnapshot> currentFiles = scanCurrentFiles();
            
            CompileState state = new CompileState();
            state.compileTime = System.currentTimeMillis();
            state.files = currentFiles;

            String json = GSON.toJson(state);
            Files.writeString(compileStateFile, json);
            
            LOGGER.info("✓ Compile state saved");
            LOGGER.debug("  Tracked {} files", currentFiles.size());
            LOGGER.debug("  State file: {}", compileStateFile);
        } catch (Exception e) {
            LOGGER.error("Failed to save compile state", e);
        }
    }

    /** 扫描 Mixin 源文件 - 使用源代码解析 */
    public void scanMixinSources() {
        if (!Files.exists(mixinSourceDir)) {
            LOGGER.info("Mixin source directory does not exist: {}", mixinSourceDir);
            return;
        }

        discoveredMixins.clear();

        try {
            Files.walk(mixinSourceDir)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(this::analyzeMixinFile);
        } catch (IOException e) {
            LOGGER.error("Failed to scan mixin sources", e);
        }

        if (discoveredMixins.isEmpty()) {
            LOGGER.info("No Mixin files found in {}", mixinSourceDir);
        } else {
            LOGGER.info("Found {} Mixin file(s):", discoveredMixins.size());
            discoveredMixins.forEach((name, info) -> {
                LOGGER.info("  - {} ({}) -> {}", name, info.side, info.targetClass);
            });
        }
    }

    /**
     * 分析 Mixin 源文件 - 从源代码提取信息
     */
    private void analyzeMixinFile(Path file) {
        try {
            String content = Files.readString(file);

            if (!content.contains("@Mixin")) {
                LOGGER.debug("File {} does not contain @Mixin annotation", file.getFileName());
                return;
            }

            String className = extractClassName(content);
            if (className == null) {
                LOGGER.warn("Could not extract class name from: {}", file);
                return;
            }

            // 从源代码提取目标类
            String targetClass = extractTargetClassFromSource(content);
            if (targetClass == null) {
                LOGGER.warn("Could not extract target class from @Mixin in: {}", file.getFileName());
                return;
            }

            Side side = determineSide(file, content);

            MixinInfo info = new MixinInfo();
            info.sourceFile = file;
            info.className = className;
            info.fullClassName = MIXIN_PACKAGE + "." + className;
            info.targetClass = targetClass;
            info.side = side;

            discoveredMixins.put(className, info);
            LOGGER.debug("Discovered mixin: {} -> {} (side: {})", className, targetClass, side);

        } catch (IOException e) {
            LOGGER.error("Failed to read mixin file: {}", file, e);
        }
    }

    /**
     * 从源代码中提取目标类
     */
    private String extractTargetClassFromSource(String source) {
        // 模式 1: @Mixin(ClassName.class)
        Pattern pattern1 = Pattern.compile("@Mixin\\s*\\(\\s*([A-Za-z][A-Za-z0-9_.]*)\\.class\\s*\\)");
        Matcher matcher1 = pattern1.matcher(source);
        if (matcher1.find()) {
            String className = matcher1.group(1);
            LOGGER.debug("  Found target via .class: {}", className);
            return className.replace('.', '/');
        }

        // 模式 2: @Mixin(value = ClassName.class)
        Pattern pattern2 = Pattern.compile("@Mixin\\s*\\(\\s*value\\s*=\\s*([A-Za-z][A-Za-z0-9_.]*)\\.class");
        Matcher matcher2 = pattern2.matcher(source);
        if (matcher2.find()) {
            String className = matcher2.group(1);
            LOGGER.debug("  Found target via value: {}", className);
            return className.replace('.', '/');
        }

        // 模式 3: @Mixin(targets = "package.ClassName")
        Pattern pattern3 = Pattern.compile("@Mixin\\s*\\(\\s*targets?\\s*=\\s*\"([^\"]+)\"");
        Matcher matcher3 = pattern3.matcher(source);
        if (matcher3.find()) {
            String className = matcher3.group(1);
            LOGGER.debug("  Found target via targets string: {}", className);
            return className.replace('.', '/');
        }

        // 模式 4: @Mixin("package.ClassName")
        Pattern pattern4 = Pattern.compile("@Mixin\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");
        Matcher matcher4 = pattern4.matcher(source);
        if (matcher4.find()) {
            String className = matcher4.group(1);
            LOGGER.debug("  Found target via string: {}", className);
            return className.replace('.', '/');
        }

        return null;
    }

    private String extractClassName(String source) {
        String cleanSource = source.replaceAll("//.*", "").replaceAll("/\\*.*?\\*/", "");
        String[] keywords = {"class ", "interface "};
        for (String keyword : keywords) {
            int idx = cleanSource.indexOf(keyword);
            if (idx >= 0) {
                String after = cleanSource.substring(idx + keyword.length());
                String[] tokens = after.split("[\\s{<]");
                if (tokens.length >= 1 && !tokens[0].trim().isEmpty()) {
                    return tokens[0].trim();
                }
            }
        }
        return null;
    }

    private Side determineSide(Path file, String content) {
        String pathStr = file.toString().toLowerCase();
        String contentLower = content.toLowerCase();

        if (pathStr.contains("/client/") || pathStr.contains("\\client\\") ||
                contentLower.contains("@onlyin(dist.client)") ||
                contentLower.contains("minecraft.class") ||
                contentLower.contains("localplayer.class") ||
                contentLower.contains("clientlevel.class")) {
            return Side.CLIENT;
        }

        if (pathStr.contains("/server/") || pathStr.contains("\\server\\") ||
                contentLower.contains("@onlyin(dist.dedicated_server)") ||
                contentLower.contains("serverplayer.class") ||
                contentLower.contains("serverlevel.class")) {
            return Side.SERVER;
        }

        return Side.COMMON;
    }

    /** 编译所有 Mixin */
    public void compileMixins(net.rain.rainjava.java.JavaSourceCompiler compiler) {
        if (discoveredMixins.isEmpty()) {
            LOGGER.info("No mixins to compile");
            return;
        }

        LOGGER.info("========================================");
        LOGGER.info("Compiling {} Mixin class(es)...", discoveredMixins.size());
        LOGGER.info("========================================");

        int success = 0;
        int failed = 0;

        try {
            if (Files.exists(mixinOutputDir)) {
                LOGGER.info("Cleaning output directory...");
                Files.walk(mixinOutputDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            if (!path.equals(mixinOutputDir)) {
                                Files.deleteIfExists(path);
                            }
                        } catch (IOException e) {
                            LOGGER.warn("Failed to delete: {}", path);
                        }
                    });
            }
            Files.createDirectories(mixinOutputDir);
        } catch (IOException e) {
            LOGGER.error("Failed to clean output directory", e);
        }

        for (MixinInfo info : discoveredMixins.values()) {
            try {
                LOGGER.info("Compiling: {} ...", info.className);
                net.rain.rainjava.java.CompiledClass compiled = compiler.compile(info.sourceFile);
                Path classFile = mixinOutputDir.resolve(info.className + ".class");
                Files.write(classFile, compiled.bytecode);
                LOGGER.info("  ✓ Compiled: {} ({} bytes)", info.className, compiled.bytecode.length);
                success++;
            } catch (Exception e) {
                LOGGER.error("  ✗ Failed to compile: {}", info.className, e);
                failed++;
            }
        }

        LOGGER.info("========================================");
        LOGGER.info("Compilation complete: {} success, {} failed", success, failed);
        LOGGER.info("========================================");

        if (success > 0) {
            mixinsCompiled = true;
        }
    }

    /** 生成 RefMap 文件 - 传递源文件和目标类信息 */
    public void generateRefMap() {
        if (discoveredMixins.isEmpty()) {
            LOGGER.info("No mixins to generate RefMap for");
            return;
        }

        LOGGER.info("========================================");
        LOGGER.info("Generating RefMap");
        LOGGER.info("========================================");

        refMapGenerator.loadMappings(null);

        // 传递源文件和目标类信息给 RefMapGenerator
        for (MixinInfo info : discoveredMixins.values()) {
            Path classFile = mixinOutputDir.resolve(info.className + ".class");
            if (Files.exists(classFile)) {
                // 传递源文件用于解析，传递目标类信息
                refMapGenerator.analyzeMixinWithSource(
                    info.className, 
                    info.sourceFile, 
                    classFile, 
                    info.targetClass
                );
            }
        }

        refMapGenerator.generateRefMap(refmapFile, REFMAP_NAME);

        Map<String, Integer> stats = refMapGenerator.getStatistics();
        LOGGER.info("RefMap Statistics:");
        LOGGER.info("  Classes: {}", stats.get("classes"));
        LOGGER.info("  Methods: {}", stats.get("methods"));
        LOGGER.info("  Fields: {}", stats.get("fields"));
        LOGGER.info("  Mixin mappings: {}", stats.get("totalMappings"));
        LOGGER.info("========================================");
    }

    /** 生成 Mixin 配置文件 */
    public void generateMixinConfig() {
        if (discoveredMixins.isEmpty()) {
            LOGGER.info("No mixins discovered, skipping config generation");
            cleanupConfigFiles();
            return;
        }

        LOGGER.info("========================================");
        LOGGER.info("Generating Mixin Configuration");
        LOGGER.info("========================================");

        List<String> commonMixins = new ArrayList<>();
        List<String> clientMixins = new ArrayList<>();
        List<String> serverMixins = new ArrayList<>();

        for (MixinInfo info : discoveredMixins.values()) {
            switch (info.side) {
                case CLIENT -> clientMixins.add(info.className);
                case SERVER -> serverMixins.add(info.className);
                case COMMON -> commonMixins.add(info.className);
            }
        }

        JsonObject config = new JsonObject();
        config.addProperty("required", true);
        config.addProperty("minVersion", "0.8");
        config.addProperty("package", MIXIN_PACKAGE);
        config.addProperty("compatibilityLevel", "JAVA_17");
        config.addProperty("refmap", REFMAP_NAME);

        if (!commonMixins.isEmpty()) {
            JsonArray arr = new JsonArray();
            commonMixins.forEach(arr::add);
            config.add("mixins", arr);
            LOGGER.info("  Common mixins: {}", commonMixins);
        }

        if (!clientMixins.isEmpty()) {
            JsonArray arr = new JsonArray();
            clientMixins.forEach(arr::add);
            config.add("client", arr);
            LOGGER.info("  Client mixins: {}", clientMixins);
        }

        if (!serverMixins.isEmpty()) {
            JsonArray arr = new JsonArray();
            serverMixins.forEach(arr::add);
            config.add("server", arr);
            LOGGER.info("  Server mixins: {}", serverMixins);
        }

        JsonObject injectors = new JsonObject();
        injectors.addProperty("defaultRequire", 1);
        config.add("injectors", injectors);

        try {
            String jsonContent = GSON.toJson(config);
            Files.writeString(mixinConfigFile, jsonContent);

            LOGGER.info("========================================");
            LOGGER.info("✓ Mixin config generated:");
            LOGGER.info("  Location: {}", mixinConfigFile);
            LOGGER.info("  Package: {}", MIXIN_PACKAGE);
            LOGGER.info("  Total mixins: {}", discoveredMixins.size());
            LOGGER.info("========================================");

        } catch (IOException e) {
            LOGGER.error("Failed to write mixin config", e);
        }
    }

    private void cleanupConfigFiles() {
        try {
            if (Files.exists(mixinConfigFile)) {
                Files.delete(mixinConfigFile);
                LOGGER.info("Deleted old mixin config file");
            }
            if (Files.exists(refmapFile)) {
                Files.delete(refmapFile);
                LOGGER.info("Deleted old refmap file");
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to delete config files", e);
        }
    }

    public void showRestartMessage() {
        if (mixinsCompiled) {
            LOGGER.warn("");
            LOGGER.warn("╔═══════════════════════════════════════╗");
            LOGGER.warn("║  ⚠   MIXINS COMPILED SUCCESSFULLY  ⚠    ║");
            LOGGER.warn("╠═══════════════════════════════════════╣");
            LOGGER.warn("║                                       ║");
            LOGGER.warn("║  Mixins will be loaded on NEXT START ║");
            LOGGER.warn("║                                       ║");
            LOGGER.warn("║  Please RESTART the game now!        ║");
            LOGGER.warn("║                                       ║");
            LOGGER.warn("╚═══════════════════════════════════════╝");
            LOGGER.warn("");
        }
    }

    public void validateConfiguration() {
        LOGGER.info("========================================");
        LOGGER.info("Validating Mixin Configuration");
        LOGGER.info("========================================");

        if (!Files.exists(mixinConfigFile)) {
            LOGGER.warn("⚠  Config file does not exist: {}", mixinConfigFile);
            LOGGER.warn("  Mixins will not be loaded until next restart");
            return;
        }

        try {
            String content = Files.readString(mixinConfigFile);
            JsonObject config = GSON.fromJson(content, JsonObject.class);

            String pkg = config.has("package") ? config.get("package").getAsString() : "";
            if (pkg.isEmpty()) {
                LOGGER.error("✗ CRITICAL: Config file has empty package!");
                LOGGER.error("  This will prevent mixins from loading!");
                return;
            }

            int totalMixins = 0;
            if (config.has("mixins")) {
                totalMixins += config.getAsJsonArray("mixins").size();
            }
            if (config.has("client")) {
                totalMixins += config.getAsJsonArray("client").size();
            }
            if (config.has("server")) {
                totalMixins += config.getAsJsonArray("server").size();
            }

            LOGGER.info("✓ Config file is valid");
            LOGGER.info("  Package: {}", pkg);
            LOGGER.info("  Total mixins: {}", totalMixins);

            int classCount = 0;
            if (Files.exists(mixinOutputDir)) {
                classCount = (int) Files.walk(mixinOutputDir)
                    .filter(p -> p.toString().endsWith(".class"))
                    .count();
            }

            LOGGER.info("  Class files: {}", classCount);

            if (Files.exists(refmapFile)) {
                long size = Files.size(refmapFile);
                LOGGER.info("✓ RefMap exists ({} bytes)", size);
            } else {
                LOGGER.warn("⚠  RefMap file not found");
            }

            if (classCount != totalMixins) {
                LOGGER.warn("⚠  Warning: Mixin count mismatch!");
                LOGGER.warn("  Config: {} mixins", totalMixins);
                LOGGER.warn("  Files:  {} class files", classCount);
            } else {
                LOGGER.info("✓ All mixins have corresponding class files");
            }

        } catch (Exception e) {
            LOGGER.error("Failed to validate config", e);
        }

        LOGGER.info("========================================");
    }

    public void runFullWorkflow(net.rain.rainjava.java.JavaSourceCompiler compiler) {
        LOGGER.info("");
        LOGGER.info("╔═══════════════════════════════════════╗");
        LOGGER.info("║    RainMixin Manager Starting...     ║");
        LOGGER.info("╚═══════════════════════════════════════╝");
        LOGGER.info("");

        scanMixinSources();

        if (!needsRecompile()) {
            LOGGER.info("✓ No recompilation needed, skipping build process");
            validateConfiguration();
            return;
        }

        LOGGER.info("Changes detected, starting compilation...");
        
        compileMixins(compiler);
        generateRefMap();
        generateMixinConfig();
        saveCompileState();
        
        validateConfiguration();
        showRestartMessage();
    }

    // Data classes
    private static class CompileState {
        long compileTime;
        Map<String, FileSnapshot> files;
    }

    private static class FileSnapshot {
        String path;
        long lastModified;
        long size;
    }

    private static class MixinInfo {
        Path sourceFile;
        String className;
        String fullClassName;
        String targetClass;  // 新增：目标类
        Side side;
    }

    private enum Side {
        COMMON, CLIENT, SERVER
    }

    // Getters
    public Path getMixinSourceDir() { return mixinSourceDir; }
    public Path getMixinOutputDir() { return mixinOutputDir; }
    public Path getMixinConfigFile() { return mixinConfigFile; }
    public Path getRefmapFile() { return refmapFile; }
    public boolean hasMixinsCompiled() { return mixinsCompiled; }
    public int getMixinCount() { return discoveredMixins.size(); }
}