package net.rain.rainjava.core;

import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.rain.rainjava.RainJava;
import net.rain.rainjava.java.JavaScriptLoader;
import net.rain.rainjava.logging.RainJavaLogger;
import net.rain.rainjava.logging.ScriptErrorCollector;
import net.rain.rainjava.resources.RainJavaResourcePack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * RainJava 核心系统，负责：
 *  - 初始化 RainJava/ 文件夹结构
 *  - 管理各脚本类型的加载器和独立 Logger
 *  - 向 Forge 注册 assets/data 资源包
 *  - 提供 reload() 接口供指令调用
 *
 * 在 MOD 事件总线上注册，由 Forge 框架驱动。
 */
@Mod.EventBusSubscriber(modid = "rainjava", bus = Mod.EventBusSubscriber.Bus.MOD)
public class RainJavaCore {

    /** RainJava 根目录名称（位于游戏根目录下） */
    private static final String RAINJAVA_FOLDER = "RainJava";

    // 各脚本类型的存放路径
    private final Path rootPath;
    private final Path serverPath;
    private final Path clientPath;
    private final Path startupPath;
    private final Path assetsPath;
    private final Path dataPath;
    private final Path coreModPath;

    /** Mixin 是否已加载，防止重复注册 */
    public boolean mixinsLoaded;

    /** 各脚本类型的独立文件 Logger（写入 logs/Java/<type>.log） */
    private final Map<ScriptType, RainJavaLogger> loggers = new HashMap<>();
    /** 各脚本类型的脚本加载器 */
    private final Map<ScriptType, JavaScriptLoader> loaders = new HashMap<>();
    /** 记录各脚本类型是否已完成初始加载，防止重复加载 */
    private final Map<ScriptType, Boolean> loadedFlags = new HashMap<>();

    // -----------------------------------------------------------------------
    // 构造与初始化
    // -----------------------------------------------------------------------

    public RainJavaCore() {
        Path gameDir = FMLPaths.GAMEDIR.get();

        // 设置各功能目录路径
        this.rootPath    = gameDir.resolve(RAINJAVA_FOLDER);
        this.serverPath  = rootPath.resolve("server");
        this.clientPath  = rootPath.resolve("client");
        this.startupPath = rootPath.resolve("startup");
        this.assetsPath  = rootPath.resolve("assets");
        this.dataPath    = rootPath.resolve("data");
        this.coreModPath = rootPath.resolve("coremod");

        // 为每个脚本类型初始化独立 Logger
        for (ScriptType type : ScriptType.values()) {
            loggers.put(type, new RainJavaLogger(type));
        }

        // 创建目录结构和示例文件
        initializeFolders();

        // 为每个脚本类型创建加载器，初始标记为未加载
        for (ScriptType type : ScriptType.values()) {
            loaders.put(type, new JavaScriptLoader(type));
            loadedFlags.put(type, false);
        }

        RainJava.LOGGER.info("RainJava Core initialized at: {}", rootPath);
    }

    // -----------------------------------------------------------------------
    // 文件夹初始化
    // -----------------------------------------------------------------------

    /** 创建所有必要的目录，并生成示例文件和 README */
    private void initializeFolders() {
        try {
            Files.createDirectories(serverPath);
            Files.createDirectories(clientPath);
            Files.createDirectories(startupPath);
            Files.createDirectories(dataPath);
            Files.createDirectories(assetsPath);
            createExampleFiles();
            RainJava.LOGGER.info("RainJava folder structure ready at: {}", rootPath);
        } catch (IOException e) {
            RainJava.LOGGER.error("Failed to create RainJava folder structure", e);
        }
    }

    /** 创建 startup/Example.java 示例脚本和 README.txt 使用说明 */
    private void createExampleFiles() {
        try {
            // 示例启动脚本（仅在首次运行时创建）
            Path exampleFile = startupPath.resolve("Example.java");
            if (!Files.exists(exampleFile)) {
                String code = """
                    package rainjava.startup;

                    /**
                     * 示例启动脚本，游戏初始化时执行一次。
                     */
                    public class Example {
                        public static void init() {
                            System.out.println("=================================");
                            System.out.println("Hello from RainJava startup script!");
                            System.out.println("=================================");
                        }
                    }
                    """;
                Files.writeString(exampleFile, code);
            }

            // README 使用说明文档
            Path readme = rootPath.resolve("README.txt");
            if (!Files.exists(readme)) {
                String txt = """
                    #######################################################################
                    #            RainJava - Dynamic Java Script & Modding System          #
                    #######################################################################

                    OVERVIEW
                    ========
                    RainJava lets you write and hot-reload Java code at Minecraft runtime
                    without restarting the game. It also provides a Mixin system for
                    method injection and a CoreMod / ASM transformer pipeline for low-level
                    bytecode patching of any game class.

                    FOLDER LAYOUT
                    =============
                      RainJava/
                      ├── startup/    Runs once when the game initialises
                      ├── server/     Runs each time the server starts
                      ├── client/     Runs during client setup
                      ├── mixins/     Mixin classes that patch game classes at runtime
                      ├── coremod/    CoreMod plugins and ASM transformer classes
                      ├── assets/     Custom resource pack (textures, models, lang, sounds)
                      ├── data/       Custom data pack (recipes, loot tables, tags)
                      └── README.txt  This file

                    SCRIPT BASICS
                    =============
                    Every script class must declare a public static init() method.
                    The package must match the folder type:

                      startup  →  package rainjava.startup;
                      server   →  package rainjava.server;
                      client   →  package rainjava.client;

                    The init() method may optionally accept a FMLJavaModLoadingContext
                    parameter, which is passed in automatically when present:

                      public static void init() { ... }
                      public static void init(FMLJavaModLoadingContext ctx) { ... }

                    Example (startup/Hello.java):

                      package rainjava.startup;
                      import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

                      public class Hello {
                          public static void init(FMLJavaModLoadingContext ctx) {
                              System.out.println("Hello from RainJava!");
                          }
                      }

                    MIXIN SYSTEM
                    ============
                    Place Mixin source files in the mixins/ folder.
                    A Mixin class must:
                      1. Implement  net.rain.api.mixin.IMixin
                      2. Override   getTargetClass() with the fully-qualified target class name
                      3. Use @Inject (with @Inject.At) to mark the injection point
                      4. Accept CallbackInfo as the last parameter of any @Inject method

                    Optional IMixin members:
                      getPriority()  — injection priority (default 1000, higher = earlier)
                      isEnabled()    — return false to disable this Mixin entirely

                    Supported @Inject.At values:
                      "HEAD"   — Before the first instruction of the method
                      "RETURN" — Before every return statement
                      "TAIL"   — Before the final return (or throw)

                    Use @Unique on helper fields/methods to prevent name collisions.

                    Mixins are compiled at startup and injected lazily when the target
                    class is first loaded. Game classes (e.g. DamageSource) can be
                    referenced freely because the compiler classpath includes all game JARs.

                    Example (mixins/MyMixin.java):

                      package rainjava.mixins;
                      import net.rain.api.mixin.IMixin;
                      import net.rain.api.mixin.annotation.Inject;
                      import net.rain.api.mixin.callback.CallbackInfo;

                      public class MyMixin implements IMixin {
                          @Override
                          public String getTargetClass() {
                              return "net.minecraft.client.Minecraft";
                          }

                          @Inject(method = "run", at = @Inject.At(value = "HEAD"))
                          public void onRun(CallbackInfo ci) {
                              System.out.println("Minecraft.run() called!");
                          }
                      }

                    COREMOD SYSTEM
                    ==============
                    CoreMods allow ASM bytecode transformation of any class before it is
                    loaded by the JVM. Place your plugin and transformer sources in
                    coremod/, then declare them in coremod/coremod.json:

                      {
                          "plugins": "rainjava.coremod.MyCoreModLoadingPlugin"
                      }

                    The plugin class must implement ICoreModLoadingPlugin, be annotated
                    with @ICoreModLoadingPlugin.Name, and return transformer class names
                    from getASMTransformerClass().

                    Each transformer implements ICoreClassTransformer:
                      ClassNode transform(String className, ClassNode basicClass)
                    Return the ClassNode unchanged if no modification is needed.

                    Example (coremod/MyCoreModLoadingPlugin.java):

                      package rainjava.coremod;
                      import net.rain.api.coremod.ICoreModLoadingPlugin;
                      import org.objectweb.asm.tree.ClassNode;
                      import java.util.Map;

                      @ICoreModLoadingPlugin.Name("MyCoremod")
                      public class MyCoreModLoadingPlugin implements ICoreModLoadingPlugin {
                          @Override
                          public String[] getASMTransformerClass() {
                              return new String[]{ "rainjava.coremod.MyTransformer" };
                          }
                          @Override
                          public void injectData(Map<String, Object> data) { }
                      }

                    Example (coremod/MyTransformer.java):

                      package rainjava.coremod;
                      import net.rain.api.coremod.ICoreClassTransformer;

                      public class MyTransformer implements ICoreClassTransformer {
                          @Override
                          public ClassNode transform(String className, ClassNode cn) {
                              // Modify cn with ASM here, then return them
                              return cn;
                          }
                      }

                    COMMANDS
                    ========
                      /java reload [startup|server|client]  Hot-reload scripts without restarting
                      /java hand getId                      Print held item registry ID
                      /java hand getClass                   Print held item Java class name
                      /java errors [startup|server|client]  Show script error summary in chat
                      /j <...>                              Alias for /java

                    LOGS
                    ====
                      logs/Java/startup.log
                      logs/Java/server.log
                      logs/Java/client.log

                    Each script type writes to its own log file for easy error isolation.
                    The main game log also receives summary entries.

                    TIPS
                    ====
                      - Mixins and CoreMods load at startup; changes require a full restart.
                      - Scripts (startup/server/client) support hot-reload via /java reload.
                      - If no system Java compiler is found, the bundled Eclipse JDT is used.
                      - Mixin method signatures must be compatible with the target method.
                      - Use @Unique on helpers to avoid name collisions with target classes.


                    ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━


                    #######################################################################
                    #          RainJava - 动态 Java 脚本与 Mod 系统                   #
                    #######################################################################

                    概述
                    ====
                    RainJava 允许你在 Minecraft 运行时编写并热重载 Java 代码，无需重启游戏。
                    同时提供 Mixin 方法注入系统，以及 CoreMod / ASM 转换器流水线，
                    可在 JVM 加载类之前对任意游戏类进行底层字节码修补。

                    目录结构
                    ========
                      RainJava/
                      ├── startup/    游戏初始化时执行一次
                      ├── server/     每次服务端启动时执行
                      ├── client/     客户端初始化时执行
                      ├── mixins/     运行时修改游戏类的 Mixin 源文件
                      ├── coremod/    CoreMod 插件与 ASM 字节码转换器
                      ├── assets/     自定义资源包（贴图、模型、语言文件、音效等）
                      ├── data/       自定义数据包（配方、战利品表、标签等）
                      └── README.txt  本文件

                    脚本基础
                    ========
                    每个脚本类必须包含 public static void init() 方法。
                    包名必须与所在目录类型对应：

                      startup  →  package rainjava.startup;
                      server   →  package rainjava.server;
                      client   →  package rainjava.client;

                    init() 方法可选地接受 FMLJavaModLoadingContext 参数，存在时会自动传入：

                      public static void init() { ... }
                      public static void init(FMLJavaModLoadingContext ctx) { ... }

                    示例（startup/Hello.java）：

                      package rainjava.startup;
                      import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

                      public class Hello {
                          public static void init(FMLJavaModLoadingContext ctx) {
                              System.out.println("Hello from RainJava!");
                          }
                      }

                    Mixin 系统
                    ==========
                    将 Mixin 源文件放入 mixins/ 目录。
                    Mixin 类必须满足：
                      1. 实现  net.rain.api.mixin.IMixin 接口
                      2. 重写  getTargetClass()，返回目标类的全限定名
                      3. @Inject 方法的最后一个参数必须是 CallbackInfo

                    IMixin 可选成员：
                      getPriority()  — 注入优先级（默认 1000，值越大越先注入）
                      isEnabled()    — 返回 false 可完全禁用该 Mixin

                    @Inject.At 支持的注入点：
                      "HEAD"   — 方法第一条指令之前
                      "RETURN" — 每个 return 语句之前
                      "TAIL"   — 最后一个 return（或 throw）之前
                      还有"FIELD"，"INVOKE"等

                    在辅助字段/方法上使用 @Unique，防止与目标类成员命名冲突。

                    Mixin 在启动阶段编译并缓存字节码，目标类首次被加载时才真正注入（懒加载）。
                    可以直接引用游戏类（如 DamageSource），编译器 classpath 已包含所有游戏 JAR。
                    可以直接使用mcp名去匹配方法名。

                    示例（mixins/MyMixin.java）：

                      package rainjava.mixins;
                      import net.rain.api.mixin.IMixin;
                      import net.rain.api.mixin.annotation.Inject;
                      import net.rain.api.mixin.callback.CallbackInfo;

                      public class MyMixin implements IMixin {
                          @Override
                          public String getTargetClass() {
                              return "net.minecraft.client.Minecraft";
                          }

                          @Inject(method = "run", at = @Inject.At(value = "HEAD"))
                          public void onRun(CallbackInfo ci) {
                              System.out.println("Minecraft.run() 被调用！");
                          }
                      }

                    CoreMod 系统
                    ============
                    CoreMod 在 JVM 加载类之前对任意类进行 ASM 字节码转换。
                    将插件和转换器源文件放入 coremod/ 目录，
                    并在 coremod/coremod.json 中声明：

                      {
                          "plugins": "rainjava.coremod.MyCoreModLoadingPlugin"
                      }

                    插件类必须实现 ICoreModLoadingPlugin，标注 @ICoreModLoadingPlugin.Name，
                    并通过 getASMTransformerClass() 返回转换器类的全限定名数组。

                    每个转换器实现 ICoreClassTransformer：
                      ClassNode transform(String className, ClassNode basicClass)
                    若不需要修改某个类，直接返回原始ClassNode即可。

                    示例（coremod/MyCoreModLoadingPlugin.java）：

                      package rainjava.coremod;
                      import net.rain.api.coremod.ICoreModLoadingPlugin;
                      import org.objectweb.asm.tree.ClassNode;
                      import java.util.Map;

                      @ICoreModLoadingPlugin.Name("MyCoremod")
                      public class MyCoreModLoadingPlugin implements ICoreModLoadingPlugin {
                          @Override
                          public String[] getASMTransformerClass() {
                              return new String[]{ "rainjava.coremod.MyTransformer" };
                          }
                          @Override
                          public void injectData(Map<String, Object> data) { }
                      }

                    示例（coremod/MyTransformer.java）：

                      package rainjava.coremod;
                      import net.rain.api.coremod.ICoreClassTransformer;

                      public class MyTransformer implements ICoreClassTransformer {
                          @Override
                          public ClassNode transform(String className, ClassNode cn) {
                              // 在此用 ASM 修改字节码，然后返回
                              return cn;
                          }
                      }

                    命令
                    ====
                      /java reload [startup|server|client]  热重载指定类型的脚本，无需重启
                      /java hand getId                      输出当前手持物品的注册表 ID
                      /java hand getClass                   输出当前手持物品的 Java 类名
                      /java errors [startup|server|client]  在聊天框中显示脚本错误摘要
                      /j <...>                              /java 的简写别名

                    日志
                    ====
                      logs/Java/startup.log
                      logs/Java/server.log
                      logs/Java/client.log

                    每种脚本类型独立写入日志文件，便于隔离定位错误。
                    主游戏日志也会收到摘要条目。

                    注意事项
                    ========
                      - Mixin 和 CoreMod 在启动时加载，修改后需要完全重启游戏。
                      - 普通脚本（startup/server/client）支持 /java reload 热重载，无需重启。
                      - 若 JRE 没有内置 Java 编译器，RainJava 会自动使用内嵌的 Eclipse JDT。
                      - Mixin 方法签名必须与目标方法兼容，否则注入时会抛出异常。
                      - 在辅助字段/方法上使用 @Unique，防止与目标类成员命名冲突。
                    """;
                Files.writeString(readme, txt);
            }
        } catch (IOException e) {
            RainJava.LOGGER.error("Failed to create example files", e);
        }
    }

    // -----------------------------------------------------------------------
    // 脚本加载
    // -----------------------------------------------------------------------

    /**
     * 首次加载指定类型的脚本。若已加载过则跳过（防止重复加载）。
     * 在 FML 生命周期事件（clientSetup、serverStarting 等）中调用。
     */
    public void loadScripts(ScriptType type) {
        if (loadedFlags.get(type)) {
            RainJava.LOGGER.debug("Scripts of type {} already loaded, skipping", type);
            return;
        }
        doLoad(type);
        loadedFlags.put(type, true);
    }

    /**
     * 强制重载指定类型的脚本。
     * 会清除旧错误、重置加载标志、重建加载器，再执行加载。
     * 由 /java reload 指令调用。
     */
    public void reload(ScriptType type) {
        RainJavaLogger logger = loggers.get(type);
        logger.info("==== Reloading {} scripts ====", type);
        // 清除旧错误，重置状态
        ScriptErrorCollector.clear(type);
        loadedFlags.put(type, false);
        // 重建加载器以获得干净的类加载器状态
        loaders.put(type, new JavaScriptLoader(type));
        doLoad(type);
        loadedFlags.put(type, true);
    }

    /**
     * 实际执行脚本加载逻辑（首次加载和重载共用）。
     * 错误会被收集到 ScriptErrorCollector 中。
     */
    private void doLoad(ScriptType type) {
        RainJavaLogger logger = loggers.get(type);
        logger.info("Loading {} scripts", type);

        JavaScriptLoader loader = loaders.get(type);
        if (loader == null || !loader.isAvailable()) {
            logger.warn("Java compiler not available, skipping {} script loading", type);
            return;
        }

        Path scriptPath = getScriptPath(type);

        try {
            // startup 类型首次加载时处理 Mixin
            if (type == ScriptType.STARTUP && !mixinsLoaded) {
                loader.processMixins();
            }
            loader.loadJavaScripts(scriptPath);
            logger.info("Finished loading {} scripts", type);
        } catch (Exception e) {
            logger.error("Failed to load {} scripts: {}", type, e.getMessage(), e);
            ScriptErrorCollector.addFromThrowable(type, type.getName(), e);
        }
    }

    // -----------------------------------------------------------------------
    // 资源包注册（MOD 总线事件）
    // -----------------------------------------------------------------------

    /**
     * 向 Forge 注册 RainJava 的 assets（客户端资源包）和 data（数据包）。
     * 这是一个静态方法，由 Forge 事件系统通过 @SubscribeEvent 自动调用。
     */
    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        try {
            Path gameDir    = FMLPaths.GAMEDIR.get();
            Path assetsPath = gameDir.resolve(RAINJAVA_FOLDER).resolve("assets");
            Path dataPath   = gameDir.resolve(RAINJAVA_FOLDER).resolve("data");

            // 注册客户端资源包（assets/）
            if (event.getPackType() == PackType.CLIENT_RESOURCES && Files.exists(assetsPath)) {
                RainJava.LOGGER.info("Registering RainJava assets pack");
                event.addRepositorySource(consumer -> {
                    PackResources pack = new RainJavaResourcePack(assetsPath, PackType.CLIENT_RESOURCES);
                    consumer.accept(Pack.readMetaAndCreate(
                            "rainjava_assets",
                            net.minecraft.network.chat.Component.literal("RainJava Assets"),
                            true,
                            name -> pack,
                            PackType.CLIENT_RESOURCES,
                            Pack.Position.TOP,
                            PackSource.DEFAULT
                    ));
                });
            }

            // 注册数据包（data/）
            if (event.getPackType() == PackType.SERVER_DATA && Files.exists(dataPath)) {
                RainJava.LOGGER.info("Registering RainJava data pack");
                event.addRepositorySource(consumer -> {
                    PackResources pack = new RainJavaResourcePack(dataPath, PackType.SERVER_DATA);
                    consumer.accept(Pack.readMetaAndCreate(
                            "rainjava_data",
                            net.minecraft.network.chat.Component.literal("RainJava Data"),
                            true,
                            name -> pack,
                            PackType.SERVER_DATA,
                            Pack.Position.TOP,
                            PackSource.DEFAULT
                    ));
                });
            }
        } catch (Exception e) {
            RainJava.LOGGER.error("Failed to register resource packs", e);
        }
    }

    // -----------------------------------------------------------------------
    // Getter 方法
    // -----------------------------------------------------------------------

    public Path getRootPath()    { return rootPath; }
    public Path getServerPath()  { return serverPath; }
    public Path getClientPath()  { return clientPath; }
    public Path getStartupPath() { return startupPath; }
    public Path getAssetsPath()  { return assetsPath; }
    public Path getDataPath()    { return dataPath; }

    /** 获取指定脚本类型的加载器 */
    public JavaScriptLoader getLoader(ScriptType type) { return loaders.get(type); }
    /** 获取指定脚本类型的独立 Logger */
    public RainJavaLogger   getLogger(ScriptType type) { return loggers.get(type); }

    /** 根据脚本类型返回对应的脚本存放目录 */
    private Path getScriptPath(ScriptType type) {
        return switch (type) {
            case SERVER  -> serverPath;
            case CLIENT  -> clientPath;
            case STARTUP -> startupPath;
        };
    }
}
