package net.rain.rainjava.java;

import java.util.HashMap;
import java.util.Map;

/**
 * 动态类加载器，用于加载编译后的类
 */
public class DynamicClassLoader extends ClassLoader {
    private final Map<String, byte[]> classBytes = new HashMap<>();
    
    public DynamicClassLoader(ClassLoader parent) {
        super(parent);
    }
    
    /**
     * 添加编译后的类
     */
    public void addCompiledClass(String className, byte[] bytecode) {
        classBytes.put(className, bytecode);
    }
    
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytecode = classBytes.get(name);
        if (bytecode != null) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
        return super.findClass(name);
    }
}