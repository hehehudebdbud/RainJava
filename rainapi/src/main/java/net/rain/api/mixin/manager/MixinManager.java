package net.rain.api.mixin.manager;

import net.rain.api.mixin.IMixin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MixinManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinManager.class);
    private static final Map<String, List<Class<?>>> MIXINS = new ConcurrentHashMap<>();
    
    public static void registerMixin(Class<?> mixinClass) {
        try {
            if (!IMixin.class.isAssignableFrom(mixinClass)) {
                LOGGER.warn("Class {} does not implement IMixin", mixinClass.getName());
                return;
            }
            
            IMixin mixin = (IMixin) mixinClass.getDeclaredConstructor().newInstance();
            if (!mixin.isEnabled()) {
                LOGGER.debug("Mixin {} is disabled", mixinClass.getSimpleName());
                return;
            }
            
            String targetClass = mixin.getTargetClass();
            MIXINS.computeIfAbsent(targetClass, k -> new ArrayList<>()).add(mixinClass);
            LOGGER.debug("Registered mixin {} for {}", mixinClass.getSimpleName(), targetClass);
        } catch (Exception e) {
            LOGGER.error("Failed to register mixin: {}", mixinClass.getName(), e);
        }
    }
    
    public static boolean hasMixins(String className) {
        return MIXINS.containsKey(className);
    }
    
    public static List<Class<?>> getMixinsFor(String className) {
        return MIXINS.getOrDefault(className, Collections.emptyList());
    }
    
    public static int getTotalMixinCount() {
        return MIXINS.values().stream().mapToInt(List::size).sum();
    }
    
    public static void clearAll() {
        MIXINS.clear();
    }
}