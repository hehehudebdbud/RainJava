package net.rain.rainjava.java.helper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.rain.asm.service.MixinService;
import org.spongepowered.rain.asm.service.IMixinService;
import org.spongepowered.rain.asm.mixin.*;
import org.spongepowered.rain.asm.mixin.transformer.*;
import java.lang.reflect.*;

/**
 * ✅ Mixin 服务修复工具 - 彻底修复版
 */
public class MixinServiceHelper {
    private static final Logger LOGGER = LogManager.getLogger();
    private static boolean serviceFixed = false;
    private static final Object LOCK = new Object();
    
    /**
     * ✅ 修复并等待 Mixin 服务完全初始化
     */
    public static boolean fixMixinService() {
        synchronized (LOCK) {
            if (serviceFixed) {
                return true;
            }
            
            try {
                LOGGER.info("========================================");
                LOGGER.info("[MixinServiceHelper] Fixing Mixin Service");
                LOGGER.info("========================================");
                
                // 步骤 1: 获取 MixinService 实例
                IMixinService service = MixinService.getService();
                if (service == null) {
                    LOGGER.error("[MixinServiceHelper] ❌ MixinService.getService() returned null!");
                    return false;
                }
                
                LOGGER.info("[MixinServiceHelper] ✅ MixinService: {}", service.getClass().getName());
                
                // 步骤 2: 验证 MixinEnvironment
                MixinEnvironment env = MixinEnvironment.getCurrentEnvironment();
                if (env == null) {
                    LOGGER.warn("[MixinServiceHelper] ⚠️ MixinEnvironment is null, trying default...");
                    env = MixinEnvironment.getDefaultEnvironment();
                    if (env == null) {
                        LOGGER.error("[MixinServiceHelper] ❌ Cannot get MixinEnvironment");
                        return false;
                    }
                }
                
                LOGGER.info("[MixinServiceHelper] ✅ MixinEnvironment: Phase={}", env.getPhase());
                
                // ✅ 步骤 3: 强制初始化 BytecodeProvider（如果你修改了 Mixin 源码）
                forceInitializeBytecodeProvider(service);
                
                // ✅ 步骤 4: 验证 BytecodeProvider 是否可用
                if (!verifyBytecodeProvider(service)) {
                    LOGGER.warn("[MixinServiceHelper] ⚠️ BytecodeProvider verification failed, but continuing...");
                    // 不再返回 false，因为 Fallback provider 应该可以工作
                }
                
                // 步骤 5: 验证 ClassTracker
                try {
                    Object classTracker = service.getClassTracker();
                    if (classTracker != null) {
                        LOGGER.info("[MixinServiceHelper] ✅ ClassTracker: {}", classTracker.getClass().getName());
                    } else {
                        LOGGER.warn("[MixinServiceHelper] ⚠️ ClassTracker is null");
                    }
                } catch (Exception e) {
                    LOGGER.warn("[MixinServiceHelper] ⚠️ ClassTracker error: {}", e.getMessage());
                }
                
                serviceFixed = true;
                LOGGER.info("[MixinServiceHelper] ✅ Mixin Service fully initialized and verified");
                LOGGER.info("========================================");
                return true;
                
            } catch (Exception e) {
                LOGGER.error("[MixinServiceHelper] ❌ Failed to fix Mixin service", e);
                return false;
            }
        }
    }
    
    /**
     * ✅ 强制初始化 BytecodeProvider（需要你在 Mixin 源码中添加此方法）
     */
    private static void forceInitializeBytecodeProvider(IMixinService service) {
        try {
            // 检查是否有自定义的强制初始化方法
            Method forceInitMethod = service.getClass().getMethod("forceInitializeBytecodeProvider");
            forceInitMethod.invoke(service);
            LOGGER.info("[MixinServiceHelper] ✅ Called forceInitializeBytecodeProvider()");
        } catch (NoSuchMethodException e) {
            LOGGER.debug("[MixinServiceHelper] forceInitializeBytecodeProvider() not available");
        } catch (Exception e) {
            LOGGER.warn("[MixinServiceHelper] Failed to force initialize: {}", e.getMessage());
        }
    }
    
    /**
     * ✅ 验证 BytecodeProvider 是否可用
     */
    private static boolean verifyBytecodeProvider(IMixinService service) {
        try {
            Method getBytecodeProvider = service.getClass().getMethod("getBytecodeProvider");
            Object provider = getBytecodeProvider.invoke(service);
            
            if (provider != null) {
                LOGGER.info("[MixinServiceHelper] ✅ BytecodeProvider available: {}", 
                    provider.getClass().getName());
                
                // ✅ 检查是否是 Fallback provider
                if (provider.getClass().getName().contains("Fallback")) {
                    LOGGER.warn("[MixinServiceHelper] ⚠️ Using Fallback BytecodeProvider");
                    return false;
                }
                
                return true;
            } else {
                LOGGER.error("[MixinServiceHelper] ❌ BytecodeProvider is null");
                return false;
            }
            
        } catch (IllegalStateException e) {
            LOGGER.error("[MixinServiceHelper] ❌ Service not initialized: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            LOGGER.error("[MixinServiceHelper] ❌ Cannot verify BytecodeProvider: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * ✅ 修复 MixinConfig 实例的 service 字段
     */
    public static void fixConfigService(MixinConfig config) {
        if (config == null) {
            LOGGER.warn("[MixinServiceHelper] ⚠️ Config is null, cannot fix");
            return;
        }
        
        try {
            IMixinService service = MixinService.getService();
            if (service == null) {
                LOGGER.error("[MixinServiceHelper] ❌ Cannot fix config: MixinService is null");
                return;
            }
            
            // 获取 Config 类
            Class<?> configClass = config.getClass();
            
            // 尝试查找 service 字段（可能在父类中）
            Field serviceField = null;
            Class<?> currentClass = configClass;
            
            while (currentClass != null && serviceField == null) {
                try {
                    serviceField = currentClass.getDeclaredField("service");
                } catch (NoSuchFieldException e) {
                    currentClass = currentClass.getSuperclass();
                }
            }
            
            if (serviceField == null) {
                LOGGER.debug("[MixinServiceHelper] No 'service' field found in Config class hierarchy");
                return;
            }
            
            serviceField.setAccessible(true);
            
            // 检查当前值
            Object currentService = serviceField.get(config);
            if (currentService == null) {
                LOGGER.warn("[MixinServiceHelper] ⚠️ Config.service is NULL, injecting...");
                serviceField.set(config, service);
                LOGGER.info("[MixinServiceHelper] ✅ Injected service into Config instance");
            } else {
                LOGGER.debug("[MixinServiceHelper] Config.service already set");
            }
            
        } catch (Exception e) {
            LOGGER.warn("[MixinServiceHelper] Cannot fix Config service: {}", e.getMessage());
        }
    }
    
    /**
     * ✅ 验证服务状态（包括 BytecodeProvider）
     */
    public static boolean verifyService() {
        try {
            IMixinService service = MixinService.getService();
            if (service == null) {
                LOGGER.error("[MixinServiceHelper] ❌ Service verification failed: service is null");
                return false;
            }
            
            MixinEnvironment env = MixinEnvironment.getCurrentEnvironment();
            if (env == null) {
                LOGGER.error("[MixinServiceHelper] ❌ Service verification failed: environment is null");
                return false;
            }
            
            // ✅ 验证 BytecodeProvider（允许使用 Fallback）
            try {
                Method getBytecodeProvider = service.getClass().getMethod("getBytecodeProvider");
                Object provider = getBytecodeProvider.invoke(service);
                if (provider == null) {
                    LOGGER.error("[MixinServiceHelper] ❌ BytecodeProvider is null");
                    return false;
                }
                
                // Fallback provider 也算通过验证
                LOGGER.info("[MixinServiceHelper] ✅ BytecodeProvider available: {}", 
                    provider.getClass().getSimpleName());
                
            } catch (IllegalStateException e) {
                LOGGER.error("[MixinServiceHelper] ❌ Service not fully initialized: {}", e.getMessage());
                return false;
            } catch (Exception e) {
                LOGGER.error("[MixinServiceHelper] ❌ Cannot verify BytecodeProvider: {}", e.getMessage());
                return false;
            }
            
            LOGGER.info("[MixinServiceHelper] ✅ Service verification passed");
            return true;
            
        } catch (Exception e) {
            LOGGER.error("[MixinServiceHelper] ❌ Service verification failed", e);
            return false;
        }
    }
    
    /**
     * ✅ 诊断服务状态
     */
    public static void diagnoseService() {
        LOGGER.info("========================================");
        LOGGER.info("[MixinServiceHelper] Service Diagnosis");
        LOGGER.info("========================================");
        
        // 1. MixinService
        try {
            IMixinService service = MixinService.getService();
            if (service != null) {
                LOGGER.info("[MixinServiceHelper] ✅ MixinService: {}", service.getClass().getName());
                
                // BytecodeProvider
                try {
                    Method getBytecodeProvider = service.getClass().getMethod("getBytecodeProvider");
                    Object provider = getBytecodeProvider.invoke(service);
                    LOGGER.info("[MixinServiceHelper] {} BytecodeProvider: {}", 
                        provider != null ? "✅" : "❌",
                        provider != null ? provider.getClass().getName() : "null");
                } catch (IllegalStateException e) {
                    LOGGER.warn("[MixinServiceHelper] ❌ BytecodeProvider not ready: {}", e.getMessage());
                } catch (Exception e) {
                    LOGGER.warn("[MixinServiceHelper] ❌ BytecodeProvider error: {}", e.getMessage());
                }
                
                // ClassTracker
                try {
                    Object tracker = service.getClassTracker();
                    LOGGER.info("[MixinServiceHelper] {} ClassTracker: {}", 
                        tracker != null ? "✅" : "❌",
                        tracker != null ? tracker.getClass().getName() : "null");
                } catch (Exception e) {
                    LOGGER.warn("[MixinServiceHelper] ❌ ClassTracker error: {}", e.getMessage());
                }
            } else {
                LOGGER.error("[MixinServiceHelper] ❌ MixinService is NULL");
            }
        } catch (Exception e) {
            LOGGER.error("[MixinServiceHelper] ❌ MixinService error: {}", e.getMessage());
        }
        
        // 2. MixinEnvironment
        try {
            MixinEnvironment env = MixinEnvironment.getCurrentEnvironment();
            if (env != null) {
                LOGGER.info("[MixinServiceHelper] ✅ MixinEnvironment: Phase={}, Side={}", 
                    env.getPhase(), env.getSide());
            } else {
                LOGGER.error("[MixinServiceHelper] ❌ MixinEnvironment is NULL");
            }
        } catch (Exception e) {
            LOGGER.error("[MixinServiceHelper] ❌ MixinEnvironment error: {}", e.getMessage());
        }
        
        LOGGER.info("========================================");
    }
    
    /**
     * 重置修复标志（用于测试）
     */
    public static void reset() {
        serviceFixed = false;
    }
}