package net.rain.rainjava;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.rain.rainjava.core.RainJavaCore;
import net.rain.rainjava.core.ScriptType;
import net.rain.rainjava.logging.RainJavaLogger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * RainJava Mod 入口点。
 *
 * 职责划分：
 *  - LOGGER（主游戏日志）仅用于 Mod 生命周期事件的日志输出，
 *    不用于脚本相关的输出（脚本日志由 RainJavaLogger 写入独立文件）。
 *  - 在 static 块中完成核心系统初始化和 startup 脚本加载。
 *  - 在 FML 事件回调中触发 client / server 脚本加载。
 */
@Mod(RainJava.MOD_ID)
public class RainJava {

    /** Mod ID，需与 mods.toml 中的定义保持一致 */
    public static final String MOD_ID = "rainjava";
    /**
     * 主游戏日志（latest.log）。
     * 仅用于 Mod 生命周期日志，脚本相关日志请使用 RainJavaLogger。
     */
    public static final Logger LOGGER = LogManager.getLogger();

    /** 全局唯一的核心实例，通过 getCore() 访问 */
    private static RainJavaCore coreInstance;

    static {
        // 静态块优先执行：初始化日志系统（创建 logs/Java/ 目录），
        // 然后启动核心系统并加载 startup 脚本。
        RainJavaLogger.of(ScriptType.STARTUP).info("init");
        coreInstance = new RainJavaCore();
        coreInstance.loadScripts(ScriptType.STARTUP);
    }

    public RainJava() {
        LOGGER.info("RainJava initializing...");

        // 注册 FML 生命周期监听器
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);
        // 注册服务端事件监听器（ServerStartingEvent）
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * 通用设置阶段（客户端和服务端均会执行）。
     * 当前暂无内容，预留给未来扩展。
     */
    private void commonSetup(FMLCommonSetupEvent event) {
        // 预留给未来使用
    }

    /**
     * 客户端设置阶段，触发 client 脚本加载。
     * 使用 client 类型的独立 Logger 记录日志。
     */
    private void clientSetup(FMLClientSetupEvent event) {
        RainJavaLogger logger = coreInstance.getLogger(ScriptType.CLIENT);
        logger.info("Client setup - loading client scripts");
        try {
            coreInstance.loadScripts(ScriptType.CLIENT);
        } catch (Exception e) {
            logger.error("Failed to load client scripts: {}", e.getMessage(), e);
        }
    }

    /**
     * 服务端启动事件，触发 server 脚本加载。
     * 使用 server 类型的独立 Logger 记录日志。
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        RainJavaLogger logger = coreInstance.getLogger(ScriptType.SERVER);
        logger.info("Server starting - loading server scripts");
        try {
            coreInstance.loadScripts(ScriptType.SERVER);
        } catch (Exception e) {
            logger.error("Failed to load server scripts: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取全局核心实例。
     * 供指令系统（RainJavaCommands）和其他外部模块调用。
     */
    public static RainJavaCore getCore() {
        return coreInstance;
    }
}
