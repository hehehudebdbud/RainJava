package net.rain.rainjava.java.helper;

import net.rain.rainjava.RainJava;
import org.spongepowered.rain.asm.mixin.transformer.*;
import org.spongepowered.rain.asm.service.*;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;

/**
 * MixinConfig 初始化辅助类
 * 解决动态创建 MixinConfig 时 plugin 字段为 null 的问题
 */
public class MixinConfigHelper {
    
    /**
     * 为 MixinConfig 设置默认的 PluginHandle
     */
    public static void initializePlugin(MixinConfig config) {
        try {
            // 首先检查是否已经有 plugin
            Field pluginField = findPluginField(config);
            if (pluginField == null) {
                throw new RuntimeException("Could not find plugin field in MixinConfig");
            }
            
            pluginField.setAccessible(true);
            Object existingPlugin = pluginField.get(config);
            
            if (existingPlugin != null) {
                RainJava.LOGGER.info("MixinConfig already has a plugin, skipping initialization");
                return;
            }
            
            // 尝试从现有的 config 中获取 plugin
            PluginHandle plugin = getPluginFromExistingConfig();
            
            if (plugin == null) {
                // 如果没有现有的 plugin，创建一个默认的（传入 config）
                plugin = createDefaultPlugin(config);
            }
            
            if (plugin != null) {
                pluginField.set(config, plugin);
                RainJava.LOGGER.info("✅ Successfully set plugin for MixinConfig: {}", config.getName());
            } else {
                RainJava.LOGGER.warn("⚠️ Could not create or find a plugin for MixinConfig");
            }
            
        } catch (Exception e) {
            RainJava.LOGGER.error("Failed to initialize plugin for MixinConfig", e);
            throw new RuntimeException("Failed to initialize MixinConfig plugin", e);
        }
    }
    
    /**
     * 查找 plugin 字段
     */
    private static Field findPluginField(MixinConfig config) {
        Class<?> current = config.getClass();
        
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField("plugin");
                return field;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        
        return null;
    }
    
    /**
     * 从现有的 MixinConfig 中获取 PluginHandle
     */
    private static PluginHandle getPluginFromExistingConfig() {
        try {
            // 获取 MixinProcessor
            MixinProcessor processor = MixinProcessorHolder.getInstance();
            if (processor == null) {
                return null;
            }
            
            // 获取现有的 configs
            Field configsField = processor.getClass().getDeclaredField("configs");
            configsField.setAccessible(true);
            
            @SuppressWarnings("unchecked")
            java.util.List<MixinConfig> configs = (java.util.List<MixinConfig>) configsField.get(processor);
            
            // 从第一个 config 中获取 plugin
            if (!configs.isEmpty()) {
                MixinConfig firstConfig = configs.get(0);
                Field pluginField = findPluginField(firstConfig);
                
                if (pluginField != null) {
                    pluginField.setAccessible(true);
                    PluginHandle plugin = (PluginHandle) pluginField.get(firstConfig);
                    
                    if (plugin != null) {
                        RainJava.LOGGER.info("Found existing plugin from config: {}", firstConfig.getName());
                        return plugin;
                    }
                }
            }
            
        } catch (Exception e) {
            RainJava.LOGGER.debug("Could not get plugin from existing config", e);
        }
        
        return null;
    }
    
    /**
     * 创建默认的 PluginHandle
     * PluginHandle 构造函数: PluginHandle(MixinConfig parent, IMixinService service, String pluginClassName)
     * 
     * 关键：如果 plugin 为 null，shouldApplyMixin() 会返回 true（允许所有 mixin）
     * 因此我们可以简单地使用 null 作为 pluginClassName
     */
    private static PluginHandle createDefaultPlugin(MixinConfig config) {
        try {
            // 获取 MixinService
            Class<?> serviceClass = Class.forName("org.spongepowered.rain.asm.service.MixinService");
            Object service = serviceClass.getMethod("getService").invoke(null);
            
            if (service == null || !(service instanceof IMixinService)) {
                RainJava.LOGGER.error("MixinService is null, cannot create PluginHandle");
                return null;
            }
            
            IMixinService mixinService = (IMixinService) service;
            
            // 使用 null 作为 pluginClassName
            // 这样 plugin 字段会是 null，但 shouldApplyMixin() 会返回 true
            Constructor<?> constructor = PluginHandle.class.getDeclaredConstructor(
                org.spongepowered.rain.asm.mixin.transformer.MixinConfig.class,
                org.spongepowered.rain.asm.service.IMixinService.class,
                String.class
            );
            
            constructor.setAccessible(true);
            PluginHandle handle = (PluginHandle) constructor.newInstance(config, mixinService, null);
            
            RainJava.LOGGER.info("✅ Created PluginHandle with null plugin (allows all mixins)");
            return handle;
            
        } catch (Exception e) {
            RainJava.LOGGER.error("Failed to create default PluginHandle，尝试备用手段！", e);
            try{
            Class<?> serviceClass = Class.forName("org.spongepowered.rain.asm.service.MixinService");
            Object service = serviceClass.getMethod("getService").invoke(null);
            if (service == null || !(service instanceof IMixinService)) {
                RainJava.LOGGER.error("MixinService is null, cannot create PluginHandle");
                return null;
            }
            
            IMixinService mixinService = (IMixinService) service;
            PluginHandle handle = new PluginHandle(config, mixinService, null);
            return handle;
            } catch (Exception err) {
            RainJava.LOGGER.error("Failed to create default PluginHandle", err);
            }
        }
        return null;
    }
    
    /**
     * 初始化 MixinConfig 的所有必要字段
     */
    public static void fullyInitializeConfig(MixinConfig config) {
        initializePlugin(config);
    }
}