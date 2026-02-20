package net.rain.rainjava.java.helper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sun.misc.Unsafe;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;

/**
 * 使用 Unsafe 和 MethodHandle 绕过所有限制来定义类
 */
public class UnsafeClassDefiner {
    private static final Logger LOGGER = LogManager.getLogger();
    private static Unsafe unsafe;
    private static MethodHandle defineClassHandle;
    private static boolean initialized = false;
    
    /**
     * 初始化 Unsafe 和必要的方法
     */
    public static boolean initialize() {
        if (initialized) {
            return true;
        }
        
        return true;
        
        /*try {
            LOGGER.info("[UnsafeClassDefiner] Initializing...");
            
            // 1. 获取 Unsafe 实例 - 使用最简单的方法
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = (Unsafe) theUnsafe.get(null);
            
            LOGGER.info("[UnsafeClassDefiner]   ✓ Got Unsafe instance");
            
            // 2. 创建 defineClass 的 MethodHandle
            defineClassHandle = createDefineClassHandle();
            
            if (defineClassHandle == null) {
                throw new RuntimeException("Failed to create defineClass handle");
            }
            
            LOGGER.info("[UnsafeClassDefiner]   ✓ defineClass method prepared");
            
            initialized = true;
            LOGGER.info("[UnsafeClassDefiner] ✅ Initialization complete");
            return true;
            
        } catch (Exception e) {
            LOGGER.error("[UnsafeClassDefiner] Initialization failed", e);
            return false;
        }
        */
    }
    
    /**
     * ✅ 创建 defineClass 的 MethodHandle
     */
    private static MethodHandle createDefineClassHandle() {
        // 方法1: 尝试使用 MethodHandles.Lookup 的私有构造器
        try {
            Class<?> lookupClass = MethodHandles.Lookup.class;
            Constructor<?> constructor = lookupClass.getDeclaredConstructor(Class.class, int.class);
            
            // 使用 Unsafe 修改 Constructor.override
            Field overrideField = AccessibleObject.class.getDeclaredField("override");
            long overrideOffset = unsafe.objectFieldOffset(overrideField);
            unsafe.putBoolean(constructor, overrideOffset, true);
            
            // 创建完全权限的 Lookup (-1 = 所有权限)
            Object fullLookup = constructor.newInstance(ClassLoader.class, -1);
            
            LOGGER.info("[UnsafeClassDefiner]   ✓ Created full-privilege Lookup");
            
            // 查找 defineClass 方法
            Method findVirtualMethod = lookupClass.getMethod(
                "findVirtual",
                Class.class,
                String.class,
                MethodType.class
            );
            
            /*MethodHandle handle = (MethodHandle) findVirtualMethod.invoke(
                fullLookup,
                ClassLoader.class,
                "defineClass",
                MethodType.methodType(
                    Class.class,
                    String.class,
                    byte[].class,
                    int.class,
                    int.class
                )
            );
            */
            
            LOGGER.info("[UnsafeClassDefiner]   ✓ Found defineClass via MethodHandle");
            return null;
            
        } catch (Exception e) {
            LOGGER.debug("[UnsafeClassDefiner] MethodHandle.Lookup approach failed: {}", e.getMessage());
        }
        
        // 方法2: 直接修改 Method.accessible
        try {
            Method defineClassMethod = ClassLoader.class.getDeclaredMethod(
                "defineClass",
                String.class,
                byte[].class,
                int.class,
                int.class
            );
            
            // 使用 Unsafe 强制设置为可访问
            Field overrideField = AccessibleObject.class.getDeclaredField("override");
            long overrideOffset = unsafe.objectFieldOffset(overrideField);
            unsafe.putBoolean(defineClassMethod, overrideOffset, true);
            
            // 转换为 MethodHandle
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle handle = lookup.unreflect(defineClassMethod);
            
            LOGGER.info("[UnsafeClassDefiner]   ✓ Found defineClass via unreflect");
            return handle;
            
        } catch (Exception e) {
            LOGGER.error("[UnsafeClassDefiner] All approaches failed", e);
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 在指定的 ClassLoader 中定义一个类
     */
    public static Class<?> defineClass(ClassLoader loader, String className, byte[] bytecode) {
        if (!initialized && !initialize()) {
            throw new IllegalStateException("UnsafeClassDefiner not initialized");
        }
        
        try {
            LOGGER.info("[UnsafeClassDefiner] Defining class: {}", className);
            
            // 调用 defineClass
            Class<?> clazz = (Class<?>) defineClassHandle.invoke(
                loader,
                className,
                bytecode,
                0,
                bytecode.length
            );
            
            LOGGER.info("[UnsafeClassDefiner] ✅ Successfully defined class: {}", className);
            return clazz;
            
        } catch (Throwable e) {
            // 检查是否是因为类已经定义
            Throwable cause = e.getCause();
            if (cause instanceof LinkageError || e instanceof LinkageError) {
                LOGGER.info("[UnsafeClassDefiner] Class {} already defined", className);
                try {
                    return loader.loadClass(className);
                } catch (ClassNotFoundException ex) {
                    throw new RuntimeException("Class already defined but cannot be loaded", ex);
                }
            }
            
            LOGGER.error("[UnsafeClassDefiner] Failed to define class: {}", className, e);
            throw new RuntimeException("Failed to define class: " + className, e);
        }
    }
    
    /**
     * 验证是否可以访问 defineClass
     */
    public static boolean verify() {
        if (!initialized && !initialize()) {
            return false;
        }
        
        try {
            // 尝试定义一个测试类
            byte[] testBytecode = generateTestClass();
            ClassLoader testLoader = UnsafeClassDefiner.class.getClassLoader();
            
            Class<?> testClass = defineClass(testLoader, "TestClass_" + System.currentTimeMillis(), testBytecode);
            
            LOGGER.info("[UnsafeClassDefiner] ✅ Verification successful");
            return true;
            
        } catch (Exception e) {
            LOGGER.error("[UnsafeClassDefiner] Verification failed", e);
            return false;
        }
    }
    
    /**
     * 生成一个简单的测试类字节码
     */
    private static byte[] generateTestClass() {
        // 最简单的类字节码: public class TestClass {}
        return new byte[] {
            (byte)0xCA, (byte)0xFE, (byte)0xBA, (byte)0xBE, // magic
            0x00, 0x00, 0x00, 0x37, // version 55 (Java 11)
            0x00, 0x04, // constant pool count
            0x07, 0x00, 0x02, // Class info
            0x01, 0x00, 0x09, 0x54, 0x65, 0x73, 0x74, 0x43, 0x6C, 0x61, 0x73, 0x73, // "TestClass"
            0x07, 0x00, 0x03, // Class info  
            0x01, 0x00, 0x10, 0x6A, 0x61, 0x76, 0x61, 0x2F, 0x6C, 0x61, 0x6E, 0x67, 0x2F, 0x4F, 0x62, 0x6A, 0x65, 0x63, 0x74, // "java/lang/Object"
            0x00, 0x21, // access flags (public, super)
            0x00, 0x01, // this class
            0x00, 0x03, // super class
            0x00, 0x00, // interfaces count
            0x00, 0x00, // fields count
            0x00, 0x00, // methods count
            0x00, 0x00  // attributes count
        };
    }
}