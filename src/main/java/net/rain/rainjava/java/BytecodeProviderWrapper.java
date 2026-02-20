package net.rain.rainjava.java.helper;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.rain.asm.service.IClassBytecodeProvider;
import net.rain.rainjava.RainJava;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ✅ BytecodeProvider 包装器
 * 拦截 getClassNode 调用，如果是我们的 Mixin 就返回缓存的字节码
 */
public class BytecodeProviderWrapper implements IClassBytecodeProvider {
    private final IClassBytecodeProvider delegate;
    private static final Map<String, byte[]> dynamicMixinBytecode = new ConcurrentHashMap<>();
    
    public BytecodeProviderWrapper(IClassBytecodeProvider delegate) {
        this.delegate = delegate;
        RainJava.LOGGER.info("✅ BytecodeProvider wrapped!");
    }
    
    /**
     * 注册动态 Mixin 的字节码
     */
    public static void registerMixin(String className, byte[] bytecode) {
        dynamicMixinBytecode.put(className, bytecode);
        dynamicMixinBytecode.put(className.replace('.', '/'), bytecode);
        RainJava.LOGGER.info("Registered dynamic mixin bytecode: {}", className);
    }
    
    /**
     * 清除所有缓存
     */
    public static void clear() {
        dynamicMixinBytecode.clear();
    }
    
    @Override
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, true);
    }
    
    @Override
    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
        // 检查是否是我们的动态 Mixin
        byte[] cachedBytecode = dynamicMixinBytecode.get(name);
        if (cachedBytecode == null) {
            cachedBytecode = dynamicMixinBytecode.get(name.replace('/', '.'));
        }
        
        if (cachedBytecode != null) {
            RainJava.LOGGER.info("✅ Providing cached bytecode for: {}", name);
            return bytesToClassNode(cachedBytecode);
        }
        
        // 否则委托给原始 provider
        return delegate.getClassNode(name, runTransformers);
    }
    
    /**
     * 将字节码转换为 ClassNode
     */
    private ClassNode bytesToClassNode(byte[] bytecode) {
        try {
            ClassNode node = new ClassNode();
            ClassReader reader = new ClassReader(bytecode);
            reader.accept(node, ClassReader.EXPAND_FRAMES);
            return node;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create ClassNode from bytecode", e);
        }
    }
    
    // 委托所有其他方法到原始 provider
    // 这里需要根据 IBytecodeProvider 的实际方法来实现
    // 如果有其他方法，也要添加委托
}

/**
 * ✅ 安装包装器的工具类
 */
class BytecodeProviderInstaller {
    
    /**
     * 安装包装器到 MixinService
     */
    public static void install() throws Exception {
        RainJava.LOGGER.info("Installing BytecodeProvider wrapper...");
        
        // 获取 MixinService
        Class<?> serviceClass = Class.forName("org.spongepowered.rain.asm.service.MixinService");
        Object service = serviceClass.getMethod("getService").invoke(null);
        
        // 获取当前的 BytecodeProvider
        Object currentProvider = service.getClass()
            .getMethod("getBytecodeProvider")
            .invoke(service);
        
        RainJava.LOGGER.info("Current provider: {}", currentProvider.getClass().getName());
        
        // 如果已经是我们的包装器，跳过
        if (currentProvider instanceof BytecodeProviderWrapper) {
            RainJava.LOGGER.info("Already wrapped, skipping");
            return;
        }
        
        // 创建包装器
        BytecodeProviderWrapper wrapper = new BytecodeProviderWrapper((IClassBytecodeProvider) currentProvider);
        
        // 查找并替换 bytecodeProvider 字段
        boolean replaced = false;
        Class<?> current = service.getClass();
        
        while (current != null && current != Object.class) {
            try {
                java.lang.reflect.Field field = current.getDeclaredField("bytecodeProvider");
                field.setAccessible(true);
                field.set(service, wrapper);
                
                RainJava.LOGGER.info("✅ Replaced bytecodeProvider field in: {}", current.getName());
                replaced = true;
                break;
                
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        
        if (!replaced) {
            throw new RuntimeException("Could not find bytecodeProvider field in MixinService");
        }
    }
}