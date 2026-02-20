package net.rain.api.coremod.transformer;

import net.rain.api.coremod.manager.CoreModManager;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;

public class PluginTransformer implements ILaunchPluginService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PluginTransformer.class);
    
    @Override
    public String name() {
        return "rain_coremod_transformer";
    }

    private static volatile Boolean hasTransformersCache = null;

    @Override
    public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty) {
        try {
            String className = classType.getClassName();
            
            // 排除自身包
            if (className.startsWith("net.rain.api.coremod")) {
                return EnumSet.noneOf(Phase.class);
            }
            
            // 排除 Java 核心类
            if (className.startsWith("java.") ||
                    className.startsWith("javax.") ||
                    className.startsWith("sun.") ||
                    className.startsWith("jdk.")) {
                return EnumSet.noneOf(Phase.class);
            }
            
            // 懒加载检查转换器
            if (hasTransformersCache == null) {
                synchronized (PluginTransformer.class) {
                    if (hasTransformersCache == null) {
                        try {
                            hasTransformersCache = CoreModManager.hasTransformers();
                        } catch (Throwable e) {
                            LOGGER.warn("Failed to check transformers", e);
                            hasTransformersCache = false;
                        }
                    }
                }
            }
            
            if (!hasTransformersCache) {
                return EnumSet.noneOf(Phase.class);
            }
            
            return EnumSet.of(Phase.BEFORE);
        } catch (Throwable e) {
            LOGGER.error("Error in handlesClass for {}", classType.getClassName(), e);
            return EnumSet.noneOf(Phase.class);
        }
    }

    @Override
    public boolean processClass(Phase phase, ClassNode classNode, Type classType) {
        if (phase != Phase.BEFORE) return false;
        
        try {
            byte[] originalBytes = classNodeToBytes(classNode);
            String className = classType.getClassName();
            byte[] transformedBytes = CoreModManager.transformClass(className, originalBytes);
            
            if (transformedBytes != originalBytes) {
                bytesToClassNode(transformedBytes, classNode);
                return true;
            }
            return false;
        } catch (Exception e) {
            LOGGER.error("Failed to transform class: {}", classType.getClassName(), e);
            return false;
        }
    }

    private byte[] classNodeToBytes(ClassNode classNode) {
        org.objectweb.asm.ClassWriter writer =
                new org.objectweb.asm.ClassWriter(0);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private void bytesToClassNode(byte[] bytes, ClassNode targetNode) {
        ClassNode newNode = new ClassNode();
        org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(bytes);
        reader.accept(newNode, 0);

        targetNode.version = newNode.version;
        targetNode.access = newNode.access;
        targetNode.name = newNode.name;
        targetNode.signature = newNode.signature;
        targetNode.superName = newNode.superName;
        targetNode.interfaces = newNode.interfaces;
        targetNode.sourceFile = newNode.sourceFile;
        targetNode.sourceDebug = newNode.sourceDebug;
        targetNode.module = newNode.module;
        targetNode.outerClass = newNode.outerClass;
        targetNode.outerMethod = newNode.outerMethod;
        targetNode.outerMethodDesc = newNode.outerMethodDesc;
        targetNode.visibleAnnotations = newNode.visibleAnnotations;
        targetNode.invisibleAnnotations = newNode.invisibleAnnotations;
        targetNode.visibleTypeAnnotations = newNode.visibleTypeAnnotations;
        targetNode.invisibleTypeAnnotations = newNode.invisibleTypeAnnotations;
        targetNode.attrs = newNode.attrs;
        targetNode.innerClasses = newNode.innerClasses;
        targetNode.nestHostClass = newNode.nestHostClass;
        targetNode.nestMembers = newNode.nestMembers;
        targetNode.permittedSubclasses = newNode.permittedSubclasses;
        targetNode.recordComponents = newNode.recordComponents;

        targetNode.fields.clear();
        targetNode.fields.addAll(newNode.fields);

        targetNode.methods.clear();
        targetNode.methods.addAll(newNode.methods);
    }
}