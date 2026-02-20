package net.rain.rainjava.java;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.rain.asm.util.Bytecode;

/**
 * Mixin 工具类
 */
public class MixinUtils {
    /**
     * 从字节码创建 ClassNode
     */
    public static ClassNode createClassNode(byte[] bytecode) {
        ClassNode classNode = new ClassNode();
        ClassReader reader = new ClassReader(bytecode);
        reader.accept(classNode, ClassReader.EXPAND_FRAMES);
        return classNode;
    }
}