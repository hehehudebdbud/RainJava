package net.rain.agent;

import java.lang.instrument.*;
import org.apache.logging.log4j.*;

public class RainMixinMainAgent {

    private static final Logger logger = LogManager.getLogger();

    public static void agentmain(String agentArgs, Instrumentation inst) {
        logger.info("[RainMixin Agent] Loading as agentmain (dynamic attach)");
        inst.addTransformer(new MixinPatcher(), true);
        //inst.addTransformer(new EntityTransformer());
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