package net.rain.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Mixin 类字节码转换器 */
public class MixinPatcher implements ClassFileTransformer {

    private static final String MIXIN_CONFIG = "org/spongepowered/asm/mixin/transformer/MixinConfig";
    private static final String MIXIN_INFO = "org/spongepowered/asm/mixin/transformer/MixinInfo";
    private static final Logger logger = LogManager.getLogger();

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        try {
            if (MIXIN_CONFIG.equals(className)) {
                logger.info("[RainMixin Agent] Intercepting MixinConfig");
                return patchMixinConfig(classfileBuffer);
            }

            if (MIXIN_INFO.equals(className)) {
                logger.info("[RainMixin Agent] Intercepting MixinInfo");
                return patchMixinInfo(classfileBuffer);
            }
        } catch (Throwable e) {
            logger.error("[RainMixin Agent] Failed to transform " + className, e);
            Runtime.getRuntime().halt(1);
        }

        return classfileBuffer;
    }

    private byte[] patchMixinConfig(byte[] originalBytes) {
        try {
            ClassNode clazz = new ClassNode();
            ClassReader cr = new ClassReader(originalBytes);
            cr.accept(clazz, ClassReader.SKIP_FRAMES);

            MethodNode createMethod = clazz.methods.stream()
                    .filter(mn -> mn.name.equals("create"))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("create method not found"));

            InsnList insertInsn = new InsnList();
            insertInsn.add(new TypeInsnNode(Opcodes.NEW, "java/io/FileInputStream"));
            insertInsn.add(new InsnNode(Opcodes.DUP));
            insertInsn.add(new VarInsnNode(Opcodes.ALOAD, 0));
            insertInsn.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    "java/io/FileInputStream", "<init>", "(Ljava/lang/String;)V", false));
            insertInsn.add(new VarInsnNode(Opcodes.ASTORE, 4));

            int index = -1;
            for (AbstractInsnNode instruction : createMethod.instructions) {
                if (instruction.getType() == AbstractInsnNode.TYPE_INSN
                        && instruction.getOpcode() == Opcodes.NEW
                        && instruction instanceof TypeInsnNode
                        && "java/lang/IllegalArgumentException".equals(((TypeInsnNode) instruction).desc)) {
                    index = createMethod.instructions.indexOf(instruction);
                    break;
                }
            }

            if (index == -1) {
                logger.error("[RainMixin Agent] Could not find injection point in MixinConfig");
                return originalBytes;
            }

            for (int i = 0; i < 12; i++) {
                createMethod.instructions.set(
                        createMethod.instructions.get(index + i),
                        new InsnNode(Opcodes.NOP)
                );
            }

            createMethod.instructions.insert(
                    createMethod.instructions.get(index),
                    insertInsn
            );

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            clazz.accept(cw);
            logger.info("[RainMixin Agent] Successfully patched MixinConfig");
            return cw.toByteArray();

        } catch (Exception e) {
            logger.error("[RainMixin Agent] Failed to patch MixinConfig", e);
            return originalBytes;
        }
    }

    private byte[] patchMixinInfo(byte[] originalBytes) {
        try {
            ClassNode clazz = new ClassNode();
            ClassReader cr = new ClassReader(originalBytes);
            // 保留帧信息用于验证
            cr.accept(clazz, ClassReader.EXPAND_FRAMES);

            // 加载注入器方法
            MethodNode injectMethod = loadInjectorMethod();
            if (injectMethod == null) {
                logger.error("[RainMixin Agent] 注入器加载失败");
                return originalBytes;
            }

            // 验证方法完整性
            if (injectMethod.name == null || injectMethod.desc == null) {
                logger.error("[RainMixin Agent] 注入方法字段为空: name={}, desc={}", 
                        injectMethod.name, injectMethod.desc);
                return originalBytes;
            }

            // 添加方法到目标类
            clazz.methods.add(injectMethod);
            logger.info("[RainMixin Agent] 注入方法成功: {} {}", injectMethod.name, injectMethod.desc);

            // 查找并修改 loadMixinClass 方法
            MethodNode method = clazz.methods.stream()
                    .filter(mn -> mn.name.equals("loadMixinClass"))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("loadMixinClass not found"));

            // 查找目标指令
            AbstractInsnNode target = findTargetInsn(method);
            if (target == null) {
                logger.error("[RainMixin Agent] 未找到目标指令");
                return originalBytes;
            }

            // 检查原始方法签名
            MethodInsnNode originalCall = (MethodInsnNode) target;
            logger.info("[RainMixin Agent] 原始调用: owner={}, name={}, desc={}", 
                    originalCall.owner, originalCall.name, originalCall.desc);

            String originalDesc = originalCall.desc;
            InsnList beforeCall = new InsnList();
            
            // 根据原始签名插入缺失的参数
            if (originalDesc.equals("(Ljava/lang/String;)Lorg/objectweb/asm/tree/ClassNode;")) {
                // 原始: (String) -> 插入 boolean, int
                logger.info("[RainMixin Agent] 插入参数: boolean, int");
                beforeCall.add(new InsnNode(Opcodes.ICONST_1));  // boolean true
                beforeCall.add(new InsnNode(Opcodes.ICONST_0));  // int 0
            } else if (originalDesc.equals("(Ljava/lang/String;Z)Lorg/objectweb/asm/tree/ClassNode;")) {
                // 原始: (String, boolean) -> 插入 int
                logger.info("[RainMixin Agent] 插入参数: int");
                beforeCall.add(new InsnNode(Opcodes.ICONST_0));  // int 0
            } else {
                logger.error("[RainMixin Agent] 不支持的原始方法签名: {}", originalDesc);
                logger.error("[RainMixin Agent] 如果您看到此错误，请提供此签名以便添加支持");
                return originalBytes;
            }
            
            // 在目标指令前插入参数
            method.instructions.insertBefore(target, beforeCall);
            
            // 创建新的方法调用指令
            MethodInsnNode newInsn = new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "org/spongepowered/asm/mixin/transformer/MixinInfo",
                    "getMixinClassNode",
                    "(Lorg/spongepowered/asm/service/IClassBytecodeProvider;Ljava/lang/String;ZI)Lorg/objectweb/asm/tree/ClassNode;",
                    false
            );

            // 替换旧指令
            method.instructions.set(target, newInsn);

            // 关键修复：完全不计算帧信息，使用原始数据
            // 这需要所有方法都保留完整的帧信息
            ClassWriter cw = new ClassWriter(0);  // 不计算任何东西
            clazz.accept(cw);
            
            logger.info("[RainMixin Agent] Successfully patched MixinInfo");
            return cw.toByteArray();

        } catch (Throwable e) {
            logger.error("[RainMixin Agent] 修补失败", e);
            e.printStackTrace();
            return originalBytes;
        }
    }

    /**
     * 清除方法中的所有帧信息
     * 这些帧引用的是原始类，在目标类中会导致验证错误
     */
    private void cleanMethodFrames(MethodNode method) {
        if (method.instructions == null) return;
        
        // 移除所有 FRAME 指令
        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null) {
            AbstractInsnNode next = insn.getNext();
            if (insn.getType() == AbstractInsnNode.FRAME) {
                method.instructions.remove(insn);
            }
            insn = next;
        }
        
        logger.info("[RainMixin Agent] 已清除方法 {} 的帧信息", method.name);
    }

    private AbstractInsnNode findTargetInsn(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == Opcodes.INVOKEINTERFACE
                    && insn instanceof MethodInsnNode
                    && ((MethodInsnNode) insn).owner != null
                    && ((MethodInsnNode) insn).owner.equals("org/spongepowered/asm/service/IClassBytecodeProvider")) {
                return insn;
            }
        }
        return null;
    }

    /**
     * 加载注入器方法 - 保留完整的帧信息
     */
    private MethodNode loadInjectorMethod() {
        try (InputStream is = getResourceAsStream()) {
            if (is == null) {
                logger.error("[RainMixin Agent] MixinInfoInjector.class not found in resources");
                return null;
            }
            
            byte[] bytes = is.readAllBytes();
            
            // 验证字节码有效性
            try {
                ClassReader testReader = new ClassReader(bytes);
                ClassNode testNode = new ClassNode();
                testReader.accept(testNode, 0);
                logger.info("[RainMixin Agent] 字节码验证通过，类名: {}", testNode.name);
            } catch (Exception e) {
                logger.error("[RainMixin Agent] 字节码损坏", e);
                return null;
            }
            
            // 读取源类 - 保留完整的帧信息
            ClassNode sourceClass = new ClassNode();
            ClassReader cr = new ClassReader(bytes);
            cr.accept(sourceClass, ClassReader.EXPAND_FRAMES);

            // 查找目标方法
            MethodNode sourceMethod = null;
            for (MethodNode m : sourceClass.methods) {
                if ("getMixinClassNode".equals(m.name)) {
                    sourceMethod = m;
                    break;
                }
            }

            if (sourceMethod == null) {
                logger.error("[RainMixin Agent] 未找到 getMixinClassNode 方法");
                return null;
            }

            logger.info("[RainMixin Agent] 找到源方法: name={}, desc={}, instructions={}, maxStack={}, maxLocals={}", 
                    sourceMethod.name, sourceMethod.desc, 
                    sourceMethod.instructions != null ? sourceMethod.instructions.size() : 0,
                    sourceMethod.maxStack, sourceMethod.maxLocals);

            // 直接返回源方法（保留完整的帧信息）
            return sourceMethod;

        } catch (Exception e) {
            logger.error("[RainMixin Agent] Failed to load injector method", e);
            return null;
        }
    }

    private static InputStream getResourceAsStream() {
        // 预定义所有可能的路径组合
        final String[] RESOURCE_PATHS = {
                "/META-INF/class/MixinInfoInjector.class",
                "META-INF/class/MixinInfoInjector.class",
                "/cpw/mods/modlauncher/MixinCore/MixinInfoInjector.class",
                "cpw/mods/modlauncher/MixinCore/MixinInfoInjector.class"
        };

        // 尝试所有 ClassLoader
        final ClassLoader[] LOADERS = {
                MixinPatcher.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader(),
                ClassLoader.getPlatformClassLoader()
        };

        InputStream is = null;

        // 遍历所有组合
        for (String path : RESOURCE_PATHS) {
            String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
            for (ClassLoader loader : LOADERS) {
                if (loader == null) continue;

                is = loader.getResourceAsStream(normalizedPath);
                if (is != null) {
                    logger.info("[RainMixin Agent] ✓ 成功加载资源: {}", path);
                    logger.info("[RainMixin Agent] ✓ 使用加载器: {}", loader.getClass().getName());
                    return is;
                }
            }
        }

        if (is == null) {
            logger.error("[RainMixin Agent] ===================================");
            logger.error("[RainMixin Agent] 致命错误：无法从任何路径加载 MixinInfoInjector.class");
            logger.error("[RainMixin Agent] 请检查 JAR 打包是否正确");
            logger.error("[RainMixin Agent] ===================================");
        }

        return null;
    }
}