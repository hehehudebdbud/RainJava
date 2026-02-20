package net.rain.agent;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class EntityTransformer implements ClassFileTransformer {
    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String PLAYER = "net/minecraft/world/entity/player/Player";
    private static final String SERVER_PLAYER = "net/minecraft/server/level/ServerPlayer";
    private static final String targetUuid = "b3fe3f6d-ccde-3a23-bbde-c018cd5eb50c";
    private static final Logger logger = LogManager.getLogger();

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        try {
            if (LIVING_ENTITY.equals(className)) {
                logger.info("[EntityTransformer] Transforming LivingEntity");
                return transformLivingEntity(classfileBuffer);
            }

            if (PLAYER.equals(className)) {
                logger.info("[EntityTransformer] Transforming Player");
                return transformPlayer(classfileBuffer);
            }
            
            if (SERVER_PLAYER.equals(className)) {
                logger.info("[EntityTransformer] Transforming ServerPlayer");
                return transformServerPlayer(classfileBuffer);
            }
        } catch (Throwable e) {
            logger.error("[EntityTransformer] Failed to transform " + className, e);
        }

        return classfileBuffer;
    }

    private byte[] transformLivingEntity(byte[] classfileBuffer) {
        ClassNode clazz = new ClassNode();
        ClassReader cr = new ClassReader(classfileBuffer);
        cr.accept(clazz, ClassReader.SKIP_FRAMES);

        // 查找 getHealth 方法 (返回 float)
        MethodNode method = clazz.methods.stream()
                .filter(m -> m.name.equals("m_21233_") && m.desc.equals("()F"))
                .findFirst()
                .orElse(null);

        if (method == null) {
            logger.warn("[EntityTransformer] getHealth method not found");
            return classfileBuffer;
        }

        // 在方法开头插入: if (this instanceof Player) return 50.0f;
        InsnList insnList = new InsnList();
        insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insnList.add(new TypeInsnNode(Opcodes.INSTANCEOF, PLAYER));
        LabelNode notPlayer = new LabelNode();
        insnList.add(new JumpInsnNode(Opcodes.IFEQ, notPlayer));
        insnList.add(new LdcInsnNode(50.0f));
        insnList.add(new InsnNode(Opcodes.FRETURN));
        insnList.add(notPlayer);

        method.instructions.insert(insnList);
        logger.info("[EntityTransformer] Patched LivingEntity.getHealth");

        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        clazz.accept(cw);
        return cw.toByteArray();
    }

    private byte[] transformPlayer(byte[] classfileBuffer) {
        ClassNode clazz = new ClassNode();
        ClassReader cr = new ClassReader(classfileBuffer);
        cr.accept(clazz, ClassReader.SKIP_FRAMES);

        // 查找 hurt 方法 (参数: DamageSource, float; 返回: boolean)
        MethodNode method = clazz.methods.stream()
                .filter(m -> m.name.equals("m_6469_") && m.desc.equals("(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
                .findFirst()
                .orElse(null);

        if (method == null) {
            logger.warn("[EntityTransformer] hurt method not found");
            return classfileBuffer;
        }

        // 清空方法体，直接返回 false
        method.instructions.clear();
        method.instructions.add(new InsnNode(Opcodes.ICONST_0)); // false
        method.instructions.add(new InsnNode(Opcodes.IRETURN));

        logger.info("[EntityTransformer] Patched Player.hurt");

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        clazz.accept(cw);
        return cw.toByteArray();
    }
    
    private byte[] transformServerPlayer(byte[] classfileBuffer) {
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassNode cn = new ClassNode();
            cr.accept(cn, ClassReader.EXPAND_FRAMES);
            int modifiedCount = 0;

            // 查找hurt方法
            for (MethodNode mn : cn.methods) {
                if (mn.name.equals("m_6469_") && mn.desc.equals("(Lnet/minecraft/world/damagesource/DamageSource;F)Z")) {
                    logger.info("[GodModeTransformer] ✓ Found hurt(DamageSource, float) method");

                    // 构建注入代码
                    InsnList injection = new InsnList();
                    
                    // this.getUUID().toString().equals(targetUuid) ? return false : continue
                    injection.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
                    injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, 
                        "net/minecraft/server/level/ServerPlayer",
                        "getUUID", "()Ljava/util/UUID;"));
                    
                    // UUID.toString()
                    injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                        "java/util/UUID", "toString", "()Ljava/lang/String;"));
                    
                    // 加载目标UUID常量
                    injection.add(new LdcInsnNode(targetUuid));
                    
                    // 调用equals
                    injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                        "java/lang/String", "equals", "(Ljava/lang/Object;)Z"));
                    
                    // 如果不相等，跳转到原始代码
                    LabelNode continueLabel = new LabelNode();
                    injection.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));
                    
                    // 如果相等，返回false (不受伤)
                    injection.add(new InsnNode(Opcodes.ICONST_0));
                    injection.add(new InsnNode(Opcodes.IRETURN));
                    
                    // 原始代码入口
                    injection.add(continueLabel);
                    
                    // 插入到方法开头
                    mn.instructions.insert(injection);
                    
                    logger.info("[GodModeTransformer] ✓ Successfully patched hurt method!");
                    modifiedCount++;
                }
                
                if (mn.name.equals("m_7500_") && mn.desc.equals("()Z")) {
                    logger.info("[GodModeTransformer] ✓ Found isCreative() method");
                    
                    // 直接清空原方法体并返回true
                    // 创建标签用于跳转到原代码
                    LabelNode originalCodeLabel = new LabelNode();
                    
                    InsnList injection = new InsnList();
                    
                    // 只在开头插入UUID检查
                    injection.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, 
                        "net/minecraft/server/level/ServerPlayer",
                        "getUUID", "()Ljava/util/UUID;"));
                    injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                        "java/util/UUID", "toString", "()Ljava/lang/String;"));
                    injection.add(new LdcInsnNode(targetUuid));
                    injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                        "java/lang/String", "equals", "(Ljava/lang/Object;)Z"));
                    
                    // 如果不匹配，跳转到原代码
                    injection.add(new JumpInsnNode(Opcodes.IFEQ, originalCodeLabel));
                    
                    // 如果匹配，返回true
                    injection.add(new InsnNode(Opcodes.ICONST_1));
                    injection.add(new InsnNode(Opcodes.IRETURN));
                    
                    // 插入到方法开头
                    mn.instructions.insert(injection);
                    
                    // 在原始代码前添加标签
                    mn.instructions.insert(originalCodeLabel);
                    
                    modifiedCount++;
                }
            }

            // 重新计算帧和最大栈深度
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    try {
                        return super.getCommonSuperClass(type1, type2);
                    } catch (Exception e) {
                        // 处理ClassNotFoundException
                        return "java/lang/Object";
                    }
                }
            };
            
            if (modifiedCount > 0) {
                logger.info("[GodModeTransformer] ✓ Successfully patched " + modifiedCount + " methods!");
            }
            
            cn.accept(cw);
            return cw.toByteArray();

        } catch (Exception e) {
            logger.info("[GodModeTransformer] ✗ Transformation failed: " + e.getMessage());
            e.printStackTrace();
            return classfileBuffer;
        }
    }
    
    private static class SafeClassWriter extends ClassWriter {

        public SafeClassWriter(int flags) {
            super(flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            // 避免加载类，直接返回 Object
            return "java/lang/Object";
        }
    }
}