package net.rain.agent;

import java.lang.instrument.*;
import org.apache.logging.log4j.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;


public class RainMixinPreAgent {

    private static final Logger logger = LogManager.getLogger();

    public static void premain(String agentArgs, Instrumentation inst) {
        logger.info("[RainMixin Agent] Loading as premain");
        //addToClassPath();
        inst.addTransformer(new MixinPatcher(), true);
        logger.info("[RainMixin Agent] Transformer registered");
        
        Class<?> mixinInfoClass = findLoadedClass(inst, "org.spongepowered.asm.mixin.transformer.MixinInfo");
        Class<?> mixinConfigClass = findLoadedClass(inst, "org.spongepowered.asm.mixin.transformer.MixinConfig");

        if (mixinInfoClass != null && mixinConfigClass != null) {
            logger.info("!!!MixinInfo和MixinConfig已加载，正在尝试重转换");
            try{
                inst.retransformClasses(mixinInfoClass, mixinConfigClass);
                logger.info("已完成对MixinInfo和MixinConfig两个类的重转换");
            } catch (UnmodifiableClassException ce) {
                logger.error("重转换类时出现了错误: {} {}", ce.getMessage(), ce);
            }
        }
       // inst.addTransformer(new EntityTransformer());
    }

    private static void addToClassPath() {
        try {
            Path rainMixinDir = Paths.get(".rain_mixin");
            if (Files.exists(rainMixinDir)) {
                URL url = rainMixinDir.toUri().toURL();

                // 添加到系统 ClassLoader
                ClassLoader classLoader = ClassLoader.getSystemClassLoader();
                if (classLoader instanceof URLClassLoader) {
                    Method addURL = URLClassLoader.class
                            .getDeclaredMethod("addURL", URL.class);
                    addURL.setAccessible(true);
                    addURL.invoke(classLoader, url);
                    logger.info("[RainMixin Agent] 成功添加到Classpath");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static Class<?> findLoadedClass(Instrumentation inst, String className) {
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (clazz.getName().equals(className)) {
                return clazz;
            }
        }
        return null;
    }
}
