// 文件: net/rain/api/mixin/transformer/MixinTransformer.java
package net.rain.api.mixin.transformer;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import net.rain.api.mixin.IMixin;
import net.rain.api.mixin.annotation.*;
import net.rain.api.mixin.manager.MixinManager;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;

import static org.objectweb.asm.Opcodes.*;

/**
 * Mixin字节码转换器 - 完整实现,不省略任何方法
 */
public class MixinTransformer implements ILaunchPluginService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinTransformer.class);
    
    @Override
    public String name() {
        return "!!MixinTransformer";
    }
    
    @Override
    public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty) {
        String className = classType.getClassName();
        
        // 排除自己的类和内部类
        if (className.startsWith("net.rain.api.mixin") || 
            className.startsWith("net.rain.api.coremod") ||
            className.startsWith("cpw.mods")) {
            return EnumSet.noneOf(Phase.class);
        }
        
        // 检查是否有mixin应用到此类
        if (MixinManager.hasMixins(className)) {
            return EnumSet.of(Phase.AFTER);
        }
        
        return EnumSet.noneOf(Phase.class);
    }
    
    @Override
    public boolean processClass(Phase phase, ClassNode classNode, Type classType, String reason) {
        if (phase != Phase.AFTER) {
            return false;
        }
        
        String className = classNode.name.replace('/', '.');
        
        if (!MixinManager.hasMixins(className)) {
            return false;
        }
        
        try {
            List<Class<?>> mixins = MixinManager.getMixinsFor(className);
            
            // 按优先级排序
            mixins.sort((a, b) -> {
                try {
                    IMixin ma = (IMixin) a.getDeclaredConstructor().newInstance();
                    IMixin mb = (IMixin) b.getDeclaredConstructor().newInstance();
                    return Integer.compare(mb.getPriority(), ma.getPriority());
                } catch (Exception e) {
                    return 0;
                }
            });
            
            for (Class<?> mixinClass : mixins) {
                applyMixin(classNode, mixinClass);
            }
            
            LOGGER.info("Applied {} mixin(s) to class {}", mixins.size(), className);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to apply mixin to class {}", className, e);
            return false;
        }
    }
    
    private void applyMixin(ClassNode targetClass, Class<?> mixinClass) {
        for (Method method : mixinClass.getDeclaredMethods()) {
            try {
                if (method.isAnnotationPresent(Inject.class)) {
                    applyInject(targetClass, mixinClass, method);
                }
                if (method.isAnnotationPresent(Redirect.class)) {
                    applyRedirect(targetClass, mixinClass, method);
                }
                if (method.isAnnotationPresent(ModifyConstant.class)) {
                    applyModifyConstant(targetClass, mixinClass, method);
                }
                if (method.isAnnotationPresent(ModifyArg.class)) {
                    applyModifyArg(targetClass, mixinClass, method);
                }
                if (method.isAnnotationPresent(ModifyArgs.class)) {
                    applyModifyArgs(targetClass, mixinClass, method);
                }
                if (method.isAnnotationPresent(ModifyVariable.class)) {
                    applyModifyVariable(targetClass, mixinClass, method);
                }
                if (method.isAnnotationPresent(Overwrite.class)) {
                    applyOverwrite(targetClass, mixinClass, method);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to apply mixin method {} from {}", 
                    method.getName(), mixinClass.getName(), e);
            }
        }
    }
    
    // ==================== @Inject 完整实现 ====================
    
    private void applyInject(ClassNode targetClass, Class<?> mixinClass, Method injectMethod) {
        Inject inject = injectMethod.getAnnotation(Inject.class);
        String targetMethodName = inject.method();
        String descriptor = inject.descriptor();
        Inject.At at = inject.at();
        boolean cancellable = inject.cancellable();
        
        for (MethodNode method : targetClass.methods) {
            if (!method.name.equals(targetMethodName)) continue;
            if (!descriptor.isEmpty() && !method.desc.equals(descriptor)) continue;
            
            List<AbstractInsnNode> injectionPoints = findInjectionPoints(method, at);
            
            if (injectionPoints.isEmpty()) {
                if (inject.require()) {
                    LOGGER.warn("No injection points found for {} in {}.{}", 
                        at.value(), targetClass.name, method.name);
                }
                continue;
            }
            
            for (AbstractInsnNode point : injectionPoints) {
                InsnList injection = createInjectCall(
                    targetClass, method, mixinClass, injectMethod, cancellable
                );
                
                if (at.before()) {
                    method.instructions.insertBefore(point, injection);
                } else {
                    method.instructions.insert(point, injection);
                }
            }
            
            method.maxStack = Math.max(method.maxStack, 10);
            method.maxLocals = Math.max(method.maxLocals, 10);
            
            LOGGER.debug("Injected {} at {} in {}.{} ({} points)", 
                injectMethod.getName(), at.value(), targetClass.name, 
                method.name, injectionPoints.size());
        }
    }
    
    private InsnList createInjectCall(ClassNode targetClass, MethodNode targetMethod,
                                     Class<?> mixinClass, Method injectMethod, 
                                     boolean cancellable) {
        InsnList insns = new InsnList();
        boolean isStatic = (targetMethod.access & ACC_STATIC) != 0;
        
        // 创建Mixin实例
        String mixinInternalName = Type.getInternalName(mixinClass);
        insns.add(new TypeInsnNode(NEW, mixinInternalName));
        insns.add(new InsnNode(DUP));
        insns.add(new MethodInsnNode(INVOKESPECIAL, mixinInternalName, "<init>", "()V", false));
        
        int mixinVarIndex = targetMethod.maxLocals++;
        insns.add(new VarInsnNode(ASTORE, mixinVarIndex));
        
        // 准备参数
        Class<?>[] paramTypes = injectMethod.getParameterTypes();
        Type[] targetParams = Type.getArgumentTypes(targetMethod.desc);
        Type returnType = Type.getReturnType(targetMethod.desc);
        
        int callbackInfoVarIndex = -1;
        insns.add(new VarInsnNode(ALOAD, mixinVarIndex));
        
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> paramType = paramTypes[i];
            String paramName = paramType.getSimpleName();
            
            if (paramName.equals("CallbackInfo") || paramName.equals("CallbackInfoReturnable")) {
                callbackInfoVarIndex = targetMethod.maxLocals++;
                
                if (paramName.equals("CallbackInfoReturnable")) {
                    String callbackClass = "net/rain/api/mixin/impl/CallbackInfoReturnableImpl";
                    insns.add(new TypeInsnNode(NEW, callbackClass));
                    insns.add(new InsnNode(DUP));
                    insns.add(new LdcInsnNode(targetClass.name + "." + targetMethod.name));
                    pushDefaultValue(insns, returnType);
                    insns.add(new MethodInsnNode(INVOKESPECIAL, callbackClass, "<init>",
                        "(Ljava/lang/String;Ljava/lang/Object;)V", false));
                } else {
                    String callbackClass = "net/rain/api/mixin/impl/CallbackInfoImpl";
                    insns.add(new TypeInsnNode(NEW, callbackClass));
                    insns.add(new InsnNode(DUP));
                    insns.add(new LdcInsnNode(targetClass.name + "." + targetMethod.name));
                    insns.add(new MethodInsnNode(INVOKESPECIAL, callbackClass, "<init>",
                        "(Ljava/lang/String;)V", false));
                }
                
                insns.add(new InsnNode(DUP));
                insns.add(new VarInsnNode(ASTORE, callbackInfoVarIndex));
            } else if (i < targetParams.length) {
                int localVarIndex = isStatic ? 0 : 1;
                for (int j = 0; j < i; j++) {
                    localVarIndex += targetParams[j].getSize();
                }
                loadLocalVariable(insns, targetParams[i], localVarIndex);
            }
        }
        
        // 调用Mixin方法
        String mixinMethodDesc = Type.getMethodDescriptor(injectMethod);
        insns.add(new MethodInsnNode(INVOKEVIRTUAL, mixinInternalName, 
            injectMethod.getName(), mixinMethodDesc, false));
        
        // 处理cancellable逻辑
        if (cancellable && callbackInfoVarIndex != -1) {
            insns.add(new VarInsnNode(ALOAD, callbackInfoVarIndex));
            insns.add(new MethodInsnNode(INVOKEINTERFACE, 
                "net/rain/api/mixin/callback/CallbackInfo", "isCancelled", "()Z", true));
            
            LabelNode continueLabel = new LabelNode();
            insns.add(new JumpInsnNode(IFEQ, continueLabel));
            
            if (returnType.getSort() == Type.VOID) {
                insns.add(new InsnNode(RETURN));
            } else if (returnType.getSort() == Type.OBJECT || returnType.getSort() == Type.ARRAY) {
                insns.add(new VarInsnNode(ALOAD, callbackInfoVarIndex));
                insns.add(new MethodInsnNode(INVOKEINTERFACE,
                    "net/rain/api/mixin/callback/CallbackInfoReturnable",
                    "getReturnValue", "()Ljava/lang/Object;", true));
                insns.add(new TypeInsnNode(CHECKCAST, returnType.getInternalName()));
                insns.add(new InsnNode(ARETURN));
            } else {
                insns.add(new VarInsnNode(ALOAD, callbackInfoVarIndex));
                insns.add(new MethodInsnNode(INVOKEINTERFACE,
                    "net/rain/api/mixin/callback/CallbackInfoReturnable",
                    "getReturnValue", "()Ljava/lang/Object;", true));
                unboxPrimitive(insns, returnType);
                insns.add(new InsnNode(getReturnOpcode(returnType)));
            }
            
            insns.add(continueLabel);
            insns.add(new FrameNode(F_SAME, 0, null, 0, null));
        }
        
        return insns;
    }
    
    // ==================== @Redirect 完整实现 ====================
    
    private void applyRedirect(ClassNode targetClass, Class<?> mixinClass, Method redirectMethod) {
        Redirect redirect = redirectMethod.getAnnotation(Redirect.class);
        Redirect.At at = redirect.at();
        String targetMethodName = redirect.method();
        String descriptor = redirect.descriptor();
        
        for (MethodNode method : targetClass.methods) {
            if (!method.name.equals(targetMethodName)) continue;
            if (!descriptor.isEmpty() && !method.desc.equals(descriptor)) continue;
            
            List<AbstractInsnNode> toRedirect = new ArrayList<>();
            int count = 0;
            
            for (AbstractInsnNode insn : method.instructions) {
                if ("INVOKE".equals(at.value()) && insn instanceof MethodInsnNode) {
                    MethodInsnNode methodInsn = (MethodInsnNode) insn;
                    if (matchesInvokeTarget(methodInsn, at.target())) {
                        if (at.ordinal() == -1 || count++ == at.ordinal()) {
                            toRedirect.add(insn);
                        }
                    }
                } else if ("FIELD".equals(at.value()) && insn instanceof FieldInsnNode) {
                    FieldInsnNode fieldInsn = (FieldInsnNode) insn;
                    if (matchesFieldTarget(fieldInsn, at.target())) {
                        if (at.ordinal() == -1 || count++ == at.ordinal()) {
                            toRedirect.add(insn);
                        }
                    }
                }
            }
            
            for (AbstractInsnNode insn : toRedirect) {
                if (insn instanceof MethodInsnNode) {
                    redirectMethodCall(method, (MethodInsnNode) insn, mixinClass, redirectMethod);
                } else if (insn instanceof FieldInsnNode) {
                    redirectFieldAccess(method, (FieldInsnNode) insn, mixinClass, redirectMethod);
                }
            }
            
            if (!toRedirect.isEmpty()) {
                LOGGER.debug("Redirected {} call(s) in {}.{}", 
                    toRedirect.size(), targetClass.name, method.name);
            }
        }
    }
    
    private void redirectMethodCall(MethodNode targetMethod, MethodInsnNode originalCall,
                                    Class<?> mixinClass, Method redirectMethod) {
        InsnList redirect = new InsnList();
        
        Type[] originalArgs = Type.getArgumentTypes(originalCall.desc);
        Type originalReturn = Type.getReturnType(originalCall.desc);
        boolean isInstanceCall = originalCall.getOpcode() != INVOKESTATIC;
        int totalArgCount = originalArgs.length + (isInstanceCall ? 1 : 0);
        
        // 保存所有参数
        int[] tempVars = new int[totalArgCount];
        for (int i = totalArgCount - 1; i >= 0; i--) {
            int varIndex = targetMethod.maxLocals++;
            tempVars[i] = varIndex;
            
            if (i == 0 && isInstanceCall) {
                redirect.add(new VarInsnNode(ASTORE, varIndex));
            } else {
                Type argType = originalArgs[i - (isInstanceCall ? 1 : 0)];
                storeLocalVariable(redirect, argType, varIndex);
            }
        }
        
        // 创建Mixin实例
        String mixinInternalName = Type.getInternalName(mixinClass);
        redirect.add(new TypeInsnNode(NEW, mixinInternalName));
        redirect.add(new InsnNode(DUP));
        redirect.add(new MethodInsnNode(INVOKESPECIAL, mixinInternalName, "<init>", "()V", false));
        
        // 重新加载参数
        for (int i = 0; i < totalArgCount; i++) {
            if (i == 0 && isInstanceCall) {
                redirect.add(new VarInsnNode(ALOAD, tempVars[i]));
            } else {
                Type argType = originalArgs[i - (isInstanceCall ? 1 : 0)];
                loadLocalVariable(redirect, argType, tempVars[i]);
            }
        }
        
        // 调用重定向方法
        String redirectDesc = Type.getMethodDescriptor(redirectMethod);
        redirect.add(new MethodInsnNode(INVOKEVIRTUAL, mixinInternalName,
            redirectMethod.getName(), redirectDesc, false));
        
        // 类型转换
        Type redirectReturn = Type.getReturnType(redirectDesc);
        if (!redirectReturn.equals(originalReturn)) {
            convertType(redirect, redirectReturn, originalReturn);
        }
        
        targetMethod.instructions.insertBefore(originalCall, redirect);
        targetMethod.instructions.remove(originalCall);
        targetMethod.maxStack = Math.max(targetMethod.maxStack, totalArgCount + 5);
    }
    
    private void redirectFieldAccess(MethodNode targetMethod, FieldInsnNode originalAccess,
                                     Class<?> mixinClass, Method redirectMethod) {
        InsnList redirect = new InsnList();
        
        Type fieldType = Type.getType(originalAccess.desc);
        boolean isGetter = originalAccess.getOpcode() == GETFIELD || 
                          originalAccess.getOpcode() == GETSTATIC;
        boolean isStatic = originalAccess.getOpcode() == GETSTATIC || 
                          originalAccess.getOpcode() == PUTSTATIC;
        
        String mixinInternalName = Type.getInternalName(mixinClass);
        
        if (isGetter) {
            if (!isStatic) {
                int objVar = targetMethod.maxLocals++;
                redirect.add(new VarInsnNode(ASTORE, objVar));
                redirect.add(new TypeInsnNode(NEW, mixinInternalName));
                redirect.add(new InsnNode(DUP));
                redirect.add(new MethodInsnNode(INVOKESPECIAL, mixinInternalName, 
                    "<init>", "()V", false));
                redirect.add(new VarInsnNode(ALOAD, objVar));
            } else {
                redirect.add(new TypeInsnNode(NEW, mixinInternalName));
                redirect.add(new InsnNode(DUP));
                redirect.add(new MethodInsnNode(INVOKESPECIAL, mixinInternalName, 
                    "<init>", "()V", false));
            }
            
            redirect.add(new MethodInsnNode(INVOKEVIRTUAL, mixinInternalName,
                redirectMethod.getName(), Type.getMethodDescriptor(redirectMethod), false));
        } else {
            int valueVar = targetMethod.maxLocals++;
            storeLocalVariable(redirect, fieldType, valueVar);
            
            int objVar = -1;
            if (!isStatic) {
                objVar = targetMethod.maxLocals++;
                redirect.add(new VarInsnNode(ASTORE, objVar));
            }
            
            redirect.add(new TypeInsnNode(NEW, mixinInternalName));
            redirect.add(new InsnNode(DUP));
            redirect.add(new MethodInsnNode(INVOKESPECIAL, mixinInternalName, 
                "<init>", "()V", false));
            
            if (!isStatic) {
                redirect.add(new VarInsnNode(ALOAD, objVar));
            }
            loadLocalVariable(redirect, fieldType, valueVar);
            
            redirect.add(new MethodInsnNode(INVOKEVIRTUAL, mixinInternalName,
                redirectMethod.getName(), Type.getMethodDescriptor(redirectMethod), false));
        }
        
        targetMethod.instructions.insertBefore(originalAccess, redirect);
        targetMethod.instructions.remove(originalAccess);
        targetMethod.maxStack = Math.max(targetMethod.maxStack, 10);
    }
    
    // ==================== @ModifyConstant 完整实现 ====================
    
    private void applyModifyConstant(ClassNode targetClass, Class<?> mixinClass, 
                                    Method modifyMethod) {
        ModifyConstant modify = modifyMethod.getAnnotation(ModifyConstant.class);
        ModifyConstant.Constant[] constants = modify.constant();
        String targetMethodName = modify.method();
        String descriptor = modify.descriptor();
        
        for (MethodNode method : targetClass.methods) {
            if (!method.name.equals(targetMethodName)) continue;
            if (!descriptor.isEmpty() && !method.desc.equals(descriptor)) continue;
            
            int totalModified = 0;
            
            for (ModifyConstant.Constant constant : constants) {
                List<AbstractInsnNode> toModify = new ArrayList<>();
                int count = 0;
                
                for (AbstractInsnNode insn : method.instructions) {
                    if (matchesConstantValue(insn, constant)) {
                        if (constant.ordinal() == -1 || count == constant.ordinal()) {
                            toModify.add(insn);
                            if (constant.ordinal() != -1) break;
                        }
                        count++;
                    }
                }
                
                for (AbstractInsnNode insn : toModify) {
                    modifyConstantInstruction(method, insn, mixinClass, modifyMethod, constant);
                    totalModified++;
                }
            }
            
            if (totalModified > 0) {
                LOGGER.debug("Modified {} constant(s) in {}.{}", 
                    totalModified, targetClass.name, method.name);
            }
        }
    }
    
    private void modifyConstantInstruction(MethodNode targetMethod, AbstractInsnNode originalInsn,
                                          Class<?> mixinClass, Method modifyMethod,
                                          ModifyConstant.Constant constantDef) {
        InsnList modifier = new InsnList();
        Type constantType = getConstantType(originalInsn, constantDef);
        
        String mixinInternalName = Type.getInternalName(mixinClass);
        modifier.add(new TypeInsnNode(NEW, mixinInternalName));
        modifier.add(new InsnNode(DUP));
        modifier.add(new MethodInsnNode(INVOKESPECIAL, mixinInternalName, 
            "<init>", "()V", false));
        
        if (constantType.getSize() == 1) {
            modifier.add(new InsnNode(SWAP));
        } else {
            modifier.add(new InsnNode(DUP_X2));
            modifier.add(new InsnNode(POP));
        }
        
        String modifyDesc = Type.getMethodDescriptor(modifyMethod);
        modifier.add(new MethodInsnNode(INVOKEVIRTUAL, mixinInternalName,
            modifyMethod.getName(), modifyDesc, false));
        
        targetMethod.instructions.insert(originalInsn, modifier);
        targetMethod.maxStack = Math.max(targetMethod.maxStack, 10);
    }
    
    private boolean matchesConstantValue(AbstractInsnNode insn, ModifyConstant.Constant constant) {
        if (insn instanceof LdcInsnNode) {
            LdcInsnNode ldc = (LdcInsnNode) insn;
            Object cst = ldc.cst;
            
            if (cst instanceof Integer && constant.intValue() != Integer.MIN_VALUE) {
                return cst.equals(constant.intValue());
            }
            if (cst instanceof Float && constant.floatValue() != Float.MIN_VALUE) {
                return Math.abs((Float)cst - constant.floatValue()) < 0.0001f;
            }
            if (cst instanceof Long && constant.longValue() != Long.MIN_VALUE) {
                return cst.equals(constant.longValue());
            }
            if (cst instanceof Double && constant.doubleValue() != Double.MIN_VALUE) {
                return Math.abs((Double)cst - constant.doubleValue()) < 0.0001;
            }
            if (cst instanceof String && !constant.stringValue().isEmpty()) {
                return cst.equals(constant.stringValue());
            }
            return false;
        }
        
        if (constant.intValue() != Integer.MIN_VALUE) {
            switch (insn.getOpcode()) {
                case ICONST_M1: return constant.intValue() == -1;
                case ICONST_0: return constant.intValue() == 0;
                case ICONST_1: return constant.intValue() == 1;
                case ICONST_2: return constant.intValue() == 2;
                case ICONST_3: return constant.intValue() == 3;
                case ICONST_4: return constant.intValue() == 4;
                case ICONST_5: return constant.intValue() == 5;
            }
            
            if (insn instanceof IntInsnNode) {
                IntInsnNode intInsn = (IntInsnNode) insn;
                if (intInsn.getOpcode() == BIPUSH || intInsn.getOpcode() == SIPUSH) {
                    return intInsn.operand == constant.intValue();
                }
            }
        }
        
        if (constant.floatValue() != Float.MIN_VALUE) {
            switch (insn.getOpcode()) {
                case FCONST_0: return Math.abs(constant.floatValue() - 0.0f) < 0.0001f;
                case FCONST_1: return Math.abs(constant.floatValue() - 1.0f) < 0.0001f;
                case FCONST_2: return Math.abs(constant.floatValue() - 2.0f) < 0.0001f;
            }
        }
        
        if (constant.longValue() != Long.MIN_VALUE) {
            switch (insn.getOpcode()) {
                case LCONST_0: return constant.longValue() == 0L;
                case LCONST_1: return constant.longValue() == 1L;
            }
        }
        
        if (constant.doubleValue() != Double.MIN_VALUE) {
            switch (insn.getOpcode()) {
                case DCONST_0: return Math.abs(constant.doubleValue() - 0.0) < 0.0001;
                case DCONST_1: return Math.abs(constant.doubleValue() - 1.0) < 0.0001;
            }
        }
        
        if (constant.nullValue() && insn.getOpcode() == ACONST_NULL) {
            return true;
        }
        
        return false;
    }
    
    private Type getConstantType(AbstractInsnNode insn, ModifyConstant.Constant constant) {
        if (insn instanceof LdcInsnNode) {
            Object cst = ((LdcInsnNode) insn).cst;
            if (cst instanceof Integer) return Type.INT_TYPE;
            if (cst instanceof Float) return Type.FLOAT_TYPE;
            if (cst instanceof Long) return Type.LONG_TYPE;
            if (cst instanceof Double) return Type.DOUBLE_TYPE;
            if (cst instanceof String) return Type.getType(String.class);
        }
        
        switch (insn.getOpcode()) {
            case ICONST_M1: case ICONST_0: case ICONST_1: case ICONST_2:
            case ICONST_3: case ICONST_4: case ICONST_5: case BIPUSH: case SIPUSH:
                return Type.INT_TYPE;
            case FCONST_0: case FCONST_1: case FCONST_2:
                return Type.FLOAT_TYPE;
            case LCONST_0: case LCONST_1:
                return Type.LONG_TYPE;
            case DCONST_0: case DCONST_1:
                return Type.DOUBLE_TYPE;
            case ACONST_NULL:
                return Type.getType(Object.class);
        }
        
        if (constant.intValue() != Integer.MIN_VALUE) return Type.INT_TYPE;
        if (constant.floatValue() != Float.MIN_VALUE) return Type.FLOAT_TYPE;
        if (constant.longValue() != Long.MIN_VALUE) return Type.LONG_TYPE;
        if (constant.doubleValue() != Double.MIN_VALUE) return Type.DOUBLE_TYPE;
        if (!constant.stringValue().isEmpty()) return Type.getType(String.class);
        
        return Type.INT_TYPE;
    }
    
    // ==================== @ModifyArg 完整实现 ====================
    
    private void applyModifyArg(ClassNode targetClass, Class<?> mixinClass, Method modifyMethod) {
        ModifyArg modify = modifyMethod.getAnnotation(ModifyArg.class);
        String targetMethodName = modify.method();
        String descriptor = modify.descriptor();
        ModifyArg.At at = modify.at();
        int argIndex = modify.index();
        
        for (MethodNode method : targetClass.methods) {
            if (!method.name.equals(targetMethodName)) continue;
            if (!descriptor.isEmpty() && !method.desc.equals(descriptor)) continue;
            
            List<AbstractInsnNode> modifyPoints = new ArrayList<>();
            int count = 0;
            
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode methodInsn = (MethodInsnNode) insn;
                    if (matchesTarget(methodInsn.name, at.target())) {
                        if (at.ordinal() == -1 || count++ == at.ordinal()) {
                            modifyPoints.add(insn);
                        }
                    }
                }
            }
            
            for (AbstractInsnNode point : modifyPoints) {
                MethodInsnNode methodInsn = (MethodInsnNode) point;
                Type[] argTypes = Type.getArgumentTypes(methodInsn.desc);
                
                if (argIndex >= argTypes.length) {
                    LOGGER.warn("Argument index {} out of bounds for method {}", 
                        argIndex, methodInsn.name);
                    continue;
                }
                
                InsnList modifier = createModifyArgCall(method, mixinClass, modifyMethod, 
                    argTypes, argIndex, methodInsn.getOpcode() != INVOKESTATIC);
                method.instructions.insertBefore(point, modifier);
            }
            
            if (!modifyPoints.isEmpty()) {
                LOGGER.debug("Modified {} argument(s) in {}.{}", 
                    modifyPoints.size(), targetClass.name, method.name);
            }
        }
    }
    
    private InsnList createModifyArgCall(MethodNode targetMethod, Class<?> mixinClass,
                                        Method modifyMethod, Type[] argTypes,
                                        int argIndex, boolean hasInstance) {
        InsnList insns = new InsnList();
        
        int totalArgs = argTypes.length + (hasInstance ? 1 : 0);
        int[] tempVars = new int[totalArgs];
        
        // 保存所有参数
        for (int i = totalArgs - 1; i >= 0; i--) {
            int varIndex = targetMethod.maxLocals++;
            tempVars[i] = varIndex;
            
            if (i == 0 && hasInstance) {
                insns.add(new VarInsnNode(ASTORE, varIndex));
            } else {
                Type type = argTypes[i - (hasInstance ? 1 : 0)];
                storeLocalVariable(insns, type, varIndex);
            }
        }
        
        // 重新加载参数,修改目标参数
        for (int i = 0; i < totalArgs; i++) {
            if (i == argIndex + (hasInstance ? 1 : 0)) {
                // 创建Mixin实例并调用修改方法
                String mixinInternalName = Type.getInternalName(mixinClass);
                insns.add(new TypeInsnNode(NEW, mixinInternalName));
                insns.add(new InsnNode(DUP));
                insns.add(new MethodInsnNode(INVOKESPECIAL, mixinInternalName, "<init>", "()V", false));
                
                Type argType = argTypes[argIndex];
                loadLocalVariable(insns, argType, tempVars[i]);
                
                String desc = Type.getMethodDescriptor(modifyMethod);
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, mixinInternalName,
                    modifyMethod.getName(), desc, false));
            } else {
                if (i == 0 && hasInstance) {
                    insns.add(new VarInsnNode(ALOAD, tempVars[i]));
                } else {
                    Type type = argTypes[i - (hasInstance ? 1 : 0)];
                    loadLocalVariable(insns, type, tempVars[i]);
                }
            }
        }
        
        targetMethod.maxStack = Math.max(targetMethod.maxStack, 10);
        return insns;
    }
    
    // ==================== @ModifyArgs 完整实现 ====================
    
    private void applyModifyArgs(ClassNode targetClass, Class<?> mixinClass, Method modifyMethod) {
        ModifyArgs modify = modifyMethod.getAnnotation(ModifyArgs.class);
        String targetMethodName = modify.method();
        String descriptor = modify.descriptor();
        ModifyArgs.At at = modify.at();
        
        for (MethodNode method : targetClass.methods) {
            if (!method.name.equals(targetMethodName)) continue;
            if (!descriptor.isEmpty() && !method.desc.equals(descriptor)) continue;
            
            List<AbstractInsnNode> modifyPoints = new ArrayList<>();
            int count = 0;
            
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode methodInsn = (MethodInsnNode) insn;
                    if (matchesTarget(methodInsn.name, at.target())) {
                        if (at.ordinal() == -1 || count++ == at.ordinal()) {
                            modifyPoints.add(insn);
                        }
                    }
                }
            }
            
            for (AbstractInsnNode point : modifyPoints) {
                MethodInsnNode methodInsn = (MethodInsnNode) point;
                Type[] argTypes = Type.getArgumentTypes(methodInsn.desc);
                boolean hasInstance = methodInsn.getOpcode() != INVOKESTATIC;
                
                InsnList modifier = createModifyArgsCall(method, mixinClass, modifyMethod,
                    argTypes, hasInstance);
                method.instructions.insertBefore(point, modifier);
            }
            
            if (!modifyPoints.isEmpty()) {
                LOGGER.debug("Modified arguments in {}.{} ({} points)",
                    targetClass.name, method.name, modifyPoints.size());
            }
        }
    }
    
    private InsnList createModifyArgsCall(MethodNode targetMethod, Class<?> mixinClass,
                                         Method modifyMethod, Type[] argTypes,
                                         boolean hasInstance) {
        InsnList insns = new InsnList();
        
        int totalArgs = argTypes.length + (hasInstance ? 1 : 0);
        int[] tempVars = new int[totalArgs];
        
        // 保存所有参数
        for (int i = totalArgs - 1; i >= 0; i--) {
            int varIndex = targetMethod.maxLocals++;
            tempVars[i] = varIndex;
            
            if (i == 0 && hasInstance) {
                insns.add(new VarInsnNode(ASTORE, varIndex));
            } else {
                Type type = argTypes[i - (hasInstance ? 1 : 0)];
                storeLocalVariable(insns, type, varIndex);
            }
        }
        
        // 创建Args对象
        insns.add(new LdcInsnNode(argTypes.length));
        insns.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object"));
        
        for (int i = 0; i < argTypes.length; i++) {
            insns.add(new InsnNode(DUP));
            insns.add(new LdcInsnNode(i));
            
            int varIdx = tempVars[i + (hasInstance ? 1 : 0)];
            loadLocalVariable(insns, argTypes[i], varIdx);
            
            if (argTypes[i].getSort() <= Type.DOUBLE) {
                boxPrimitive(insns, argTypes[i]);
            }
            
            insns.add(new InsnNode(AASTORE));
        }
        
        int argsVar = targetMethod.maxLocals++;
        insns.add(new TypeInsnNode(NEW, "net/rain/api/mixin/impl/ArgsImpl"));
        insns.add(new InsnNode(DUP_X1));
        insns.add(new InsnNode(SWAP));
        insns.add(new MethodInsnNode(INVOKESPECIAL, "net/rain/api/mixin/impl/ArgsImpl",
            "<init>", "([Ljava/lang/Object;)V", false));
        insns.add(new InsnNode(DUP));
        insns.add(new VarInsnNode(ASTORE, argsVar));
        
        String mixinInternalName = Type.getInternalName(mixinClass);
        insns.add(new TypeInsnNode(NEW, mixinInternalName));
        insns.add(new InsnNode(DUP));
        insns.add(new MethodInsnNode(INVOKESPECIAL, mixinInternalName, "<init>", "()V", false));
        insns.add(new InsnNode(SWAP));
        
        String desc = Type.getMethodDescriptor(modifyMethod);
        insns.add(new MethodInsnNode(INVOKEVIRTUAL, mixinInternalName,
            modifyMethod.getName(), desc, false));
        
        // 从Args中取出修改后的参数
        if (hasInstance) {
            insns.add(new VarInsnNode(ALOAD, tempVars[0]));
        }
        
        for (int i = 0; i < argTypes.length; i++) {
            insns.add(new VarInsnNode(ALOAD, argsVar));
            insns.add(new LdcInsnNode(i));
            insns.add(new MethodInsnNode(INVOKEINTERFACE, "net/rain/api/mixin/callback/Args",
                "get", "(I)Ljava/lang/Object;", true));
            
            if (argTypes[i].getSort() <= Type.DOUBLE) {
                unboxPrimitive(insns, argTypes[i]);
            } else {
                insns.add(new TypeInsnNode(CHECKCAST, argTypes[i].getInternalName()));
            }
        }
        
        targetMethod.maxStack = Math.max(targetMethod.maxStack, 15);
        return insns;
    }
    
    // ==================== @ModifyVariable 完整实现 ====================
    
    private void applyModifyVariable(ClassNode targetClass, Class<?> mixinClass,
                                    Method modifyMethod) {
        ModifyVariable modify = modifyMethod.getAnnotation(ModifyVariable.class);
        String targetMethodName = modify.method();
        String descriptor = modify.descriptor();
        
        for (MethodNode method : targetClass.methods) {
            if (!method.name.equals(targetMethodName)) continue;
            if (!descriptor.isEmpty() && !method.desc.equals(descriptor)) continue;
            
            List<AbstractInsnNode> points = findVariablePoints(method, modify.at());
            
            for (AbstractInsnNode point : points) {
                InsnList modifier = createModifyVariableCall(method, mixinClass,
                    modifyMethod, modify.index());
                method.instructions.insert(point, modifier);
            }
            
            if (!points.isEmpty()) {
                LOGGER.debug("Modified {} variable(s) in {}.{}",
                    points.size(), targetClass.name, method.name);
            }
        }
    }
    
    private InsnList createModifyVariableCall(MethodNode targetMethod, Class<?> mixinClass,
                                             Method modifyMethod, int index) {
        InsnList insns = new InsnList();
        
        String mixinInternalName = Type.getInternalName(mixinClass);
        insns.add(new TypeInsnNode(NEW, mixinInternalName));
        insns.add(new InsnNode(DUP));
        insns.add(new MethodInsnNode(INVOKESPECIAL, mixinInternalName, "<init>", "()V", false));
        insns.add(new InsnNode(SWAP));
        
        String desc = Type.getMethodDescriptor(modifyMethod);
        insns.add(new MethodInsnNode(INVOKEVIRTUAL, mixinInternalName,
            modifyMethod.getName(), desc, false));
        
        targetMethod.maxStack = Math.max(targetMethod.maxStack, 10);
        return insns;
    }
    
    private List<AbstractInsnNode> findVariablePoints(MethodNode method, ModifyVariable.At at) {
        List<AbstractInsnNode> points = new ArrayList<>();
        int count = 0;
        
        for (AbstractInsnNode insn : method.instructions) {
            if (isStoreInstruction(insn) && "STORE".equals(at.value())) {
                if (at.ordinal() == -1 || count++ == at.ordinal()) {
                    points.add(insn);
                }
            } else if (isLoadInstruction(insn) && "LOAD".equals(at.value())) {
                if (at.ordinal() == -1 || count++ == at.ordinal()) {
                    points.add(insn);
                }
            }
        }
        
        return points;
    }
    
    // ==================== @Overwrite 完整实现 ====================
    
    private void applyOverwrite(ClassNode targetClass, Class<?> mixinClass,
                               Method overwriteMethod) {
        Overwrite overwrite = overwriteMethod.getAnnotation(Overwrite.class);
        String targetMethodName = overwrite.method();
        
        for (int i = 0; i < targetClass.methods.size(); i++) {
            MethodNode method = targetClass.methods.get(i);
            
            if (method.name.equals(targetMethodName)) {
                try {
                    ClassReader reader = new ClassReader(mixinClass.getName());
                    ClassNode mixinNode = new ClassNode();
                    reader.accept(mixinNode, 0);
                    
                    for (MethodNode mixinMethod : mixinNode.methods) {
                        if (mixinMethod.name.equals(overwriteMethod.getName())) {
                            targetClass.methods.set(i, cloneMethod(mixinMethod, method.name));
                            LOGGER.info("Overwrote {}.{}", targetClass.name, method.name);
                            return;
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to overwrite method", e);
                }
            }
        }
    }
    
    private MethodNode cloneMethod(MethodNode source, String newName) {
        MethodNode clone = new MethodNode(source.access, newName, source.desc,
            source.signature, source.exceptions.toArray(new String[0]));
        source.accept(clone);
        return clone;
    }
    
    // ==================== 辅助方法(完整实现) ====================
    
    private List<AbstractInsnNode> findInjectionPoints(MethodNode method, Inject.At at) {
        List<AbstractInsnNode> points = new ArrayList<>();
        
        switch (at.value()) {
            case "HEAD":
                points.add(method.instructions.getFirst());
                break;
            case "TAIL":
            case "RETURN":
                for (AbstractInsnNode insn : method.instructions) {
                    if (isReturnInstruction(insn)) {
                        points.add(insn);
                    }
                }
                break;
            case "INVOKE":
                findInvokePoints(method, at, points);
                break;
            case "FIELD":
                findFieldPoints(method, at, points);
                break;
            case "NEW":
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn.getOpcode() == NEW) {
                        points.add(insn);
                    }
                }
                break;
        }
        
        if (at.shift() != 0 && !points.isEmpty()) {
            points = applyShift(points, at.shift());
        }
        
        return points;
    }
    
    private void findInvokePoints(MethodNode method, Inject.At at, List<AbstractInsnNode> points) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode methodInsn = (MethodInsnNode) insn;
                if (matchesTarget(methodInsn.name, at.target())) {
                    if (at.ordinal() == -1 || count++ == at.ordinal()) {
                        points.add(insn);
                    }
                }
            }
        }
    }
    
    private void findFieldPoints(MethodNode method, Inject.At at, List<AbstractInsnNode> points) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode) {
                FieldInsnNode fieldInsn = (FieldInsnNode) insn;
                if (matchesTarget(fieldInsn.name, at.target())) {
                    if (at.ordinal() == -1 || count++ == at.ordinal()) {
                        points.add(insn);
                    }
                }
            }
        }
    }
    
    private List<AbstractInsnNode> applyShift(List<AbstractInsnNode> points, int shift) {
        List<AbstractInsnNode> shifted = new ArrayList<>();
        for (AbstractInsnNode point : points) {
            AbstractInsnNode target = point;
            int s = shift;
            
            while (s > 0 && target.getNext() != null) {
                target = target.getNext();
                s--;
            }
            while (s < 0 && target.getPrevious() != null) {
                target = target.getPrevious();
                s++;
            }
            
            shifted.add(target);
        }
        return shifted;
    }
    
    private boolean isReturnInstruction(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return opcode >= IRETURN && opcode <= RETURN;
    }
    
    private boolean isStoreInstruction(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return (opcode >= ISTORE && opcode <= ASTORE) || insn instanceof VarInsnNode;
    }
    
    private boolean isLoadInstruction(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return (opcode >= ILOAD && opcode <= ALOAD) || insn instanceof VarInsnNode;
    }
    
    private boolean matchesTarget(String actual, String pattern) {
        if (pattern.isEmpty()) return false;
        return actual.equals(pattern) || actual.contains(pattern) || pattern.contains(actual);
    }
    
    private boolean matchesInvokeTarget(MethodInsnNode insn, String target) {
        if (target.isEmpty()) return false;
        
        if (!target.contains(";") && !target.contains("(")) {
            return insn.name.equals(target);
        }
        
        String fullTarget = "L" + insn.owner + ";" + insn.name + insn.desc;
        return fullTarget.equals(target) || fullTarget.contains(target);
    }
    
    private boolean matchesFieldTarget(FieldInsnNode insn, String target) {
        if (target.isEmpty()) return false;
        
        if (!target.contains(";") && !target.contains(":")) {
            return insn.name.equals(target);
        }
        
        String fullTarget = "L" + insn.owner + ";" + insn.name + ":" + insn.desc;
        return fullTarget.equals(target) || fullTarget.contains(target);
    }
    
    private void loadLocalVariable(InsnList insns, Type type, int index) {
        insns.add(new VarInsnNode(type.getOpcode(ILOAD), index));
    }
    
    private void storeLocalVariable(InsnList insns, Type type, int index) {
        insns.add(new VarInsnNode(type.getOpcode(ISTORE), index));
    }
    
    private void pushDefaultValue(InsnList insns, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN: case Type.BYTE: case Type.CHAR: case Type.SHORT: case Type.INT:
                insns.add(new InsnNode(ICONST_0));
                insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf",
                    "(I)Ljava/lang/Integer;", false));
                break;
            case Type.FLOAT:
                insns.add(new InsnNode(FCONST_0));
                insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Float", "valueOf",
                    "(F)Ljava/lang/Float;", false));
                break;
            case Type.LONG:
                insns.add(new InsnNode(LCONST_0));
                insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Long", "valueOf",
                    "(J)Ljava/lang/Long;", false));
                break;
            case Type.DOUBLE:
                insns.add(new InsnNode(DCONST_0));
                insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Double", "valueOf",
                    "(D)Ljava/lang/Double;", false));
                break;
            default:
                insns.add(new InsnNode(ACONST_NULL));
                break;
        }
    }
    
    private void boxPrimitive(InsnList insns, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Boolean", "valueOf",
                    "(Z)Ljava/lang/Boolean;", false));
                break;
            case Type.BYTE:
                insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Byte", "valueOf",
                    "(B)Ljava/lang/Byte;", false));
                break;
            case Type.CHAR:
                insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Character", "valueOf",
                    "(C)Ljava/lang/Character;", false));
                break;
            case Type.SHORT:
                insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Short", "valueOf",
                    "(S)Ljava/lang/Short;", false));
                break;
            case Type.INT:
                insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf",
                    "(I)Ljava/lang/Integer;", false));
                break;
            case Type.FLOAT:
                insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Float", "valueOf",
                    "(F)Ljava/lang/Float;", false));
                break;
            case Type.LONG:
                insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Long", "valueOf",
                    "(J)Ljava/lang/Long;", false));
                break;
            case Type.DOUBLE:
                insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Double", "valueOf",
                    "(D)Ljava/lang/Double;", false));
                break;
        }
    }
    
    private void unboxPrimitive(InsnList insns, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                insns.add(new TypeInsnNode(CHECKCAST, "java/lang/Boolean"));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Boolean",
                    "booleanValue", "()Z", false));
                break;
            case Type.BYTE:
                insns.add(new TypeInsnNode(CHECKCAST, "java/lang/Byte"));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Byte",
                    "byteValue", "()B", false));
                break;
            case Type.CHAR:
                insns.add(new TypeInsnNode(CHECKCAST, "java/lang/Character"));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Character",
                    "charValue", "()C", false));
                break;
            case Type.SHORT:
                insns.add(new TypeInsnNode(CHECKCAST, "java/lang/Short"));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Short",
                    "shortValue", "()S", false));
                break;
            case Type.INT:
                insns.add(new TypeInsnNode(CHECKCAST, "java/lang/Integer"));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Integer",
                    "intValue", "()I", false));
                break;
            case Type.FLOAT:
                insns.add(new TypeInsnNode(CHECKCAST, "java/lang/Float"));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Float",
                    "floatValue", "()F", false));
                break;
            case Type.LONG:
                insns.add(new TypeInsnNode(CHECKCAST, "java/lang/Long"));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Long",
                    "longValue", "()J", false));
                break;
            case Type.DOUBLE:
                insns.add(new TypeInsnNode(CHECKCAST, "java/lang/Double"));
                insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Double",
                    "doubleValue", "()D", false));
                break;
        }
    }
    
    private void convertType(InsnList insns, Type from, Type to) {
        if (from.equals(to)) return;
        
        if (from.getSort() <= Type.DOUBLE && to.getSort() == Type.OBJECT) {
            boxPrimitive(insns, from);
        } else if (from.getSort() == Type.OBJECT && to.getSort() <= Type.DOUBLE) {
            unboxPrimitive(insns, to);
        } else if (from.getSort() == Type.OBJECT && to.getSort() == Type.OBJECT) {
            insns.add(new TypeInsnNode(CHECKCAST, to.getInternalName()));
        }
    }
    
    private int getReturnOpcode(Type type) {
        return type.getOpcode(IRETURN);
    }
}