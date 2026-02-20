package net.rain.rainjava.java;

/**
 * 编译后的类
 */
public class CompiledClass {
    public final String className;
    public final byte[] bytecode;
    
    public CompiledClass(String className, byte[] bytecode) {
        this.className = className;
        this.bytecode = bytecode;
    }
}