package net.rain.rainjava.java.helper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.lang.reflect.*;
import java.util.*;

/** Rain Mixin 专用模块打开器 专门为 org.spongepowered.asm.mixin 设计 */
public class RuntimeModuleOpener {
    private static final Logger LOGGER = LogManager.getLogger();
    private static boolean initialized = false;
    private static boolean javaBaseOpened = false;
    private static final Object LOCK = new Object();

    // Rain Mixin 包前缀(固定)
    private static final String RAIN_MIXIN_PACKAGE = "org.spongepowered.asm.mixin";
    private static final String RAIN_TOOLS_PACKAGE = "org.spongepowered.tools.obfuscation";

    /** ✅ 确保模块间的双向访问 (使用更激进的方法) */
    private static void ensureBidirectionalAccess(Object javaBaseModule, Object targetModule,
            String pkg, Class<?> moduleClass) {
        try {
            // 方法1: 使用 implAddOpens (绕过权限检查)
            try {
                Method implAddOpens = moduleClass.getDeclaredMethod("implAddOpens", String.class, moduleClass);
                implAddOpens.setAccessible(true);
                implAddOpens.invoke(javaBaseModule, pkg, targetModule);
                //LOGGER.info("[RuntimeModuleOpener]   ✔ Forced open {} to {} via implAddOpens",
                     //=   pkg, getModuleName(targetModule));
                return;
            } catch (Exception e) {
                LOGGER.debug("implAddOpens failed: {}", e.getMessage());
            }

            // 方法2: 使用 Unsafe 直接修改模块对象
            try {
                Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);

                // 修改 openPackages 字段
                Field openPackagesField = moduleClass.getDeclaredField("openPackages");
                long offset = unsafe.objectFieldOffset(openPackagesField);
                Object openPackages = unsafe.getObject(javaBaseModule, offset);

                if (openPackages instanceof Map) {
                    Map<String, Set<Object>> map = (Map<String, Set<Object>>) openPackages;
                    Set<Object> targets = map.computeIfAbsent(pkg, k -> new HashSet<>());
                    targets.add(targetModule);

                    // 同时添加 EVERYONE_MODULE
                    try {
                        Field everyoneField = moduleClass.getDeclaredField("EVERYONE_MODULE");
                        everyoneField.setAccessible(true);
                        Object everyone = everyoneField.get(null);
                        targets.add(everyone);
                    } catch (NoSuchFieldException ignored) {
                    }

                    //LOGGER.info("[RuntimeModuleOpener]   ✔ Forced open {} via Unsafe", pkg);
                }
            } catch (Exception e) {
                LOGGER.debug("Unsafe bidirectional access failed: {}", e.getMessage());
            }

        } catch (Exception e) {
            LOGGER.debug("ensureBidirectionalAccess failed: {}", e.getMessage());
        }
    }

// 在 RuntimeModuleOpener.java 的 openJavaBaseModule() 方法中修改

    public static void openJavaBaseModule() {
        if (javaBaseOpened) {
            return;
        }

        synchronized (LOCK) {
            if (javaBaseOpened) return;

            try {
                //LOGGER.info("========================================");
                //LOGGER.info("[RuntimeModuleOpener] Opening java.base module...");
                //LOGGER.info("========================================");

                // 获取当前模块
                Object currentModule = getModuleOf(RuntimeModuleOpener.class);

                // ✅ 获取 Mixin 模块
                Object mixinModule = getMixinModule();

                // 获取 java.base 模块
                Object javaBaseModule = getJavaBaseModule();

                if (javaBaseModule == null) {
                    LOGGER.error("[RuntimeModuleOpener] Failed to get java.base module!");
                    return;
                }

                Class<?> moduleClass = javaBaseModule.getClass();

                // 需要打开的关键包
                String[] criticalPackages = {
                        "java.lang",
                        "java.lang.reflect",
                        "java.util",
                        "jdk.internal.reflect"
                };

                //LOGGER.info("[RuntimeModuleOpener] Opening {} java.base packages...", criticalPackages.length);

                int i = 0;

                for (String pkg : criticalPackages) {
                    // 打开给所有未命名模块
                    openPackageToAllUnnamed(javaBaseModule, pkg, moduleClass);
                    exportPackageToAllUnnamed(javaBaseModule, pkg, moduleClass);

                    // 打开给当前模块 (rainjava)
                    if (currentModule != null) {
                        openPackageToModule(javaBaseModule, pkg, currentModule, moduleClass);
                        exportPackageToModule(javaBaseModule, pkg, currentModule, moduleClass);
                        ensureBidirectionalAccess(javaBaseModule, currentModule, pkg, moduleClass);
                    }

                    // ✅ 关键：打开给 Mixin 模块
                    if (mixinModule != null) {
                        openPackageToModule(javaBaseModule, pkg, mixinModule, moduleClass);
                        exportPackageToModule(javaBaseModule, pkg, mixinModule, moduleClass);
                        ensureBidirectionalAccess(javaBaseModule, mixinModule, pkg, moduleClass);
                        //LOGGER.info("[RuntimeModuleOpener]   ✓ Opened {} to Mixin module", pkg);
                    }
                }

                // 使用 Unsafe 强制打开
                tryUnsafeOpenJavaBase(javaBaseModule, criticalPackages);

                //LOGGER.info("========================================");
                //LOGGER.info("[RuntimeModuleOpener] ✅ java.base module opened successfully");
                //LOGGER.info("========================================");

                javaBaseOpened = true;

            } catch (Exception e) {
                LOGGER.error("[RuntimeModuleOpener] Failed to open java.base module", e);
            }
        }
    }

/** ✅ 新增：获取 Mixin 模块 */
    private static Object getMixinModule() {
        try {
            // 尝试通过 Mixin 的核心类获取模块
            Class<?> mixinClass = Class.forName("org.spongepowered.asm.mixin.Mixin");
            Object module = getModuleOf(mixinClass);

            if (module != null) {
                String moduleName = getModuleName(module);
                //LOGGER.info("[RuntimeModuleOpener] ✅ Found Mixin module: {}", moduleName);
                return module;
            }
        } catch (ClassNotFoundException e) {
            LOGGER.warn("[RuntimeModuleOpener] Mixin class not found");
        }

        return null;
    }

    /** ✅ 获取 java.base 模块 */
    private static Object getJavaBaseModule() {
        try {
            // 方法1: 通过 Object.class 获取
            Object module = getModuleOf(Object.class);
            if (module != null) {
                String name = getModuleName(module);
                if ("java.base".equals(name)) {
                    //LOGGER.info("[RuntimeModuleOpener] ✅ Found java.base via Object.class");
                    return module;
                }
            }

            // 方法2: 通过 ModuleLayer 查找
            try {
                Class<?> moduleLayerClass = Class.forName("java.lang.ModuleLayer");
                Object bootLayer = moduleLayerClass.getMethod("boot").invoke(null);
                Set<?> modules = (Set<
                                ?>) bootLayer.getClass().getMethod("modules").invoke(bootLayer);

                for (Object mod : modules) {
                    String name = getModuleName(mod);
                    if ("java.base".equals(name)) {
                        //LOGGER.info("[RuntimeModuleOpener] ✅ Found java.base via ModuleLayer");
                        return mod;
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("[RuntimeModuleOpener] ModuleLayer lookup failed: {}", e.getMessage());
            }

        } catch (Exception e) {
            LOGGER.error("[RuntimeModuleOpener] Failed to get java.base module: {}", e.getMessage());
        }

        return null;
    }

    /** ✅ 打开包给所有未命名模块 */
    private static void openPackageToAllUnnamed(Object sourceModule, String pkg, Class<
                    ?> moduleClass) {
        try {
            // 检查包是否存在
            Method getPackagesMethod = moduleClass.getMethod("getPackages");
            Set<String> packages = (Set<String>) getPackagesMethod.invoke(sourceModule);

            if (!packages.contains(pkg)) {
                LOGGER.trace("[RuntimeModuleOpener] Package {} not in module, skipping", pkg);
                return;
            }

            // 尝试 implAddOpensToAllUnnamed
            try {
                Method implAddOpensToAllUnnamed = moduleClass.getDeclaredMethod(
                        "implAddOpensToAllUnnamed", String.class
                );
                implAddOpensToAllUnnamed.setAccessible(true);
                implAddOpensToAllUnnamed.invoke(sourceModule, pkg);
                //LOGGER.info("[RuntimeModuleOpener]   ✔ Opened {} to all unnamed modules", pkg);
                return;
            } catch (NoSuchMethodException e) {
                LOGGER.debug("implAddOpensToAllUnnamed not available");
            }

            // 备用方案：使用 Module.addOpens
            try {
                Method addOpensMethod = moduleClass.getMethod("addOpens", String.class, moduleClass);
                Object unnamedModule = getUnnamedModule();
                if (unnamedModule != null) {
                    addOpensMethod.invoke(sourceModule, pkg, unnamedModule);
                    //LOGGER.info("[RuntimeModuleOpener]   ✔ Opened {} to unnamed module", pkg);
                }
            } catch (Exception e) {
                LOGGER.debug("addOpens to unnamed failed: {}", e.getMessage());
            }

        } catch (Exception e) {
            LOGGER.debug("[RuntimeModuleOpener] Could not open package {}: {}", pkg, e.getMessage());
        }
    }

    /** ✅ 导出包给所有未命名模块 */
    private static void exportPackageToAllUnnamed(Object sourceModule, String pkg, Class<
                    ?> moduleClass) {
        try {
            Method getPackagesMethod = moduleClass.getMethod("getPackages");
            Set<String> packages = (Set<String>) getPackagesMethod.invoke(sourceModule);

            if (!packages.contains(pkg)) {
                return;
            }

            try {
                Method implAddExportsToAllUnnamed = moduleClass.getDeclaredMethod(
                        "implAddExportsToAllUnnamed", String.class
                );
                implAddExportsToAllUnnamed.setAccessible(true);
                implAddExportsToAllUnnamed.invoke(sourceModule, pkg);
                //LOGGER.info("[RuntimeModuleOpener]   ✔ Exported {} to all unnamed modules", pkg);
                return;
            } catch (NoSuchMethodException e) {
                LOGGER.debug("implAddExportsToAllUnnamed not available");
            }

        } catch (Exception e) {
            LOGGER.debug("[RuntimeModuleOpener] Could not export package {}: {}", pkg, e.getMessage());
        }
    }

    /** ✅ 获取未命名模块 */
    private static Object getUnnamedModule() {
        try {
            ClassLoader cl = ClassLoader.getSystemClassLoader();
            Method getUnnamedModule = ClassLoader.class.getMethod("getUnnamedModule");
            return getUnnamedModule.invoke(cl);
        } catch (Exception e) {
            return null;
        }
    }

    /** ✅ 使用 Unsafe 强制打开 java.base 模块 */
    private static void tryUnsafeOpenJavaBase(Object module, String[] packages) {
        try {
            //LOGGER.info("[RuntimeModuleOpener] Attempting Unsafe method for java.base...");

            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);

            Class<?> moduleClass = module.getClass();

            // 修改 openPackages 字段
            try {
                Field openPackagesField = moduleClass.getDeclaredField("openPackages");
                long offset = unsafe.objectFieldOffset(openPackagesField);
                Object openPackages = unsafe.getObject(module, offset);

                if (openPackages instanceof Map) {
                    Map<String, Set<Object>> map = (Map<String, Set<Object>>) openPackages;
                    for (String pkg : packages) {
                        Set<Object> targets = map.get(pkg);
                        if (targets == null) {
                            targets = new HashSet<>();
                            map.put(pkg, targets);
                        }
                        // 添加 EVERYONE_MODULE 或 ALL_UNNAMED_MODULE
                        try {
                            Field everyoneField = moduleClass.getDeclaredField("EVERYONE_MODULE");
                            everyoneField.setAccessible(true);
                            Object everyone = everyoneField.get(null);
                            targets.add(everyone);
                        } catch (NoSuchFieldException e) {
                            // 尝试其他标记
                        }
                    }
                    //LOGGER.info("[RuntimeModuleOpener]   ✔ Used Unsafe to open java.base packages");
                }
            } catch (NoSuchFieldException e) {
                LOGGER.debug("openPackages field not found");
            }

        } catch (Exception e) {
            LOGGER.debug("Unsafe method for java.base failed: {}", e.getMessage());
        }
    }

    /** 在任何使用Mixin处理器之前调用 */
    public static void openMixinModules() {
        if (initialized) {
            return;
        }

        synchronized (LOCK) {
            if (initialized) return;

            try {
                //LOGGER.info("========================================");
                //LOGGER.info("[RuntimeModuleOpener] Opening Rain Mixin modules at runtime...");
                //LOGGER.info("========================================");

                // ✅ 首先打开 java.base 模块
                openJavaBaseModule();

                // 1. 加载 Rain Mixin 注解类
                Class<?> mixinClass;
                try {
                    mixinClass = Class.forName(RAIN_MIXIN_PACKAGE + ".Mixin");
                    //LOGGER.info("[RuntimeModuleOpener] ✅ Found Rain Mixin: {}", RAIN_MIXIN_PACKAGE);
                } catch (ClassNotFoundException e) {
                    LOGGER.warn("[RuntimeModuleOpener] Rain Mixin not found, skipping module opening");
                    initialized = true;
                    return;
                }

                // 2. 获取Module对象
                Object mixinModule = getModuleOf(mixinClass);
                if (mixinModule == null) {
                    //LOGGER.info("[RuntimeModuleOpener] Mixin is not in a named module");
                    initialized = true;
                    return;
                }

                // 3. 获取需要访问的目标模块
                Object currentModule = getModuleOf(RuntimeModuleOpener.class);

                // 4. 获取ECJ编译器模块
                Object ecjModule = getECJModule();

                // 5. 构建需要打开的包列表(Rain专用)
                String[] criticalPackages = {
                        // Mixin核心包
                        RAIN_MIXIN_PACKAGE,
                        RAIN_MIXIN_PACKAGE + ".transformer",
                        RAIN_MIXIN_PACKAGE + ".transformer.ext",
                        RAIN_MIXIN_PACKAGE + ".injection",
                        RAIN_MIXIN_PACKAGE + ".injection.struct",
                        RAIN_MIXIN_PACKAGE + ".injection.callback",
                        RAIN_MIXIN_PACKAGE + ".injection.invoke",
                        RAIN_MIXIN_PACKAGE + ".injection.throwables",
                        RAIN_MIXIN_PACKAGE + ".gen",
                        "org.spongepowered.asm.service",
                        "org.spongepowered.asm.util",
                        "org.spongepowered.asm.lib",
                        "org.spongepowered.asm.mixin.transformer",

                        // 工具包(注解处理器所在)
                        RAIN_TOOLS_PACKAGE,
                        RAIN_TOOLS_PACKAGE + ".interfaces",
                        RAIN_TOOLS_PACKAGE + ".mirror",
                        RAIN_TOOLS_PACKAGE + ".struct",
                        RAIN_TOOLS_PACKAGE + ".mapping"
                };

                //LOGGER.info("[RuntimeModuleOpener] Opening {} critical packages...", criticalPackages.length);

                Class<?> moduleClass = mixinModule.getClass();

                // 6. 打开所有关键包
                for (String pkg : criticalPackages) {
                    // 打开给所有未命名模块
                    openPackage(mixinModule, pkg, moduleClass);
                    exportPackage(mixinModule, pkg, moduleClass);

                    // 打开给当前模块
                    if (currentModule != null) {
                        openPackageToModule(mixinModule, pkg, currentModule, moduleClass);
                        exportPackageToModule(mixinModule, pkg, currentModule, moduleClass);
                    }

                    // 打开给ECJ模块(关键!)
                    if (ecjModule != null) {
                        openPackageToModule(mixinModule, pkg, ecjModule, moduleClass);
                        exportPackageToModule(mixinModule, pkg, ecjModule, moduleClass);
                    }
                }

                // 7. 使用Unsafe强制打开
                tryUnsafeOpen(mixinModule, criticalPackages);

                //LOGGER.info("========================================");
                //LOGGER.info("[RuntimeModuleOpener] ✅ All Rain Mixin modules opened successfully");
                //LOGGER.info("========================================");

                // 8. 验证访问
                verifyAccess();

                initialized = true;

            } catch (Exception e) {
                LOGGER.error("[RuntimeModuleOpener] Failed to open modules", e);
                LOGGER.error("Stacktrace:", e);
                initialized = true;
            }
        }
    }

    /** 获取ECJ编译器的模块 */
    private static Object getECJModule() {
        try {
            // 尝试通过核心类获取模块
            String[] ecjClasses = {
                    "org.eclipse.jdt.internal.compiler.tool.EclipseCompiler",
                    "org.eclipse.jdt.internal.compiler.Compiler",
                    "org.eclipse.jdt.internal.compiler.batch.Main",
                    "org.eclipse.jdt.internal.compiler.apt.dispatch.BatchAnnotationProcessorManager"
            };

            for (String className : ecjClasses) {
                try {
                    Class<?> ecjClass = Class.forName(className);
                    Object module = getModuleOf(ecjClass);
                    if (module != null) {
                        String moduleName = getModuleName(module);
                        //LOGGER.info("[RuntimeModuleOpener] ✅ Found ECJ module: {}", moduleName);
                        return module;
                    }
                } catch (ClassNotFoundException ignored) {
                }
            }

            // 通过ModuleLayer查找
            try {
                Class<?> moduleLayerClass = Class.forName("java.lang.ModuleLayer");
                Object bootLayer = moduleLayerClass.getMethod("boot").invoke(null);
                Set<?> modules = (Set<
                                ?>) bootLayer.getClass().getMethod("modules").invoke(bootLayer);

                for (Object module : modules) {
                    String name = getModuleName(module);
                    if ("ecj".equals(name) || name.contains("eclipse.jdt")) {
                        //LOGGER.info("[RuntimeModuleOpener] ✅ Found ECJ module via ModuleLayer: {}", name);
                        return module;
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("[RuntimeModuleOpener] ModuleLayer lookup failed: {}", e.getMessage());
            }

        } catch (Exception e) {
            LOGGER.warn("[RuntimeModuleOpener] Failed to detect ECJ module: {}", e.getMessage());
        }

        //LOGGER.info("[RuntimeModuleOpener] ℹ️ ECJ module not detected (OK if using system compiler)");
        return null;
    }

    /** 获取模块名称 */
    private static String getModuleName(Object module) {
        try {
            Method getName = module.getClass().getMethod("getName");
            return (String) getName.invoke(module);
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 打开包到特定模块 */
    private static void openPackageToModule(Object sourceModule, String pkg, Object targetModule, Class<
                    ?> moduleClass) {
        try {
            // 检查包是否存在
            Method getPackagesMethod = moduleClass.getMethod("getPackages");
            Set<String> packages = (Set<String>) getPackagesMethod.invoke(sourceModule);

            if (!packages.contains(pkg)) {
                LOGGER.trace("[RuntimeModuleOpener] Package {} not in module, skipping", pkg);
                return;
            }

            // 检查是否已打开
            Method isOpenMethod = moduleClass.getMethod("isOpen", String.class, moduleClass);
            boolean isOpen = (Boolean) isOpenMethod.invoke(sourceModule, pkg, targetModule);

            if (isOpen) {
                LOGGER.trace("[RuntimeModuleOpener] Package {} already open to {}", pkg, getModuleName(targetModule));
                return;
            }

            // 方法1: 标准addOpens
            try {
                Method addOpens = moduleClass.getMethod("addOpens", String.class, moduleClass);
                addOpens.invoke(sourceModule, pkg, targetModule);
                //LOGGER.info("[RuntimeModuleOpener]   ✔ Opened {} to {}", pkg, getModuleName(targetModule));
                return;
            } catch (Exception e) {
                LOGGER.debug("Standard addOpens failed: {}", e.getMessage());
            }

            // 方法2: 内部implAddOpens
            try {
                Method implAddOpens = moduleClass.getDeclaredMethod("implAddOpens", String.class, moduleClass);
                implAddOpens.setAccessible(true);
                implAddOpens.invoke(sourceModule, pkg, targetModule);
                //LOGGER.info("[RuntimeModuleOpener]   ✔ Opened {} to {} (impl)", pkg, getModuleName(targetModule));
            } catch (Exception e) {
                LOGGER.debug("implAddOpens failed: {}", e.getMessage());
            }

        } catch (Exception e) {
            LOGGER.debug("[RuntimeModuleOpener] Could not open package {}: {}", pkg, e.getMessage());
        }
    }

    /** 导出包到特定模块 */
    private static void exportPackageToModule(Object sourceModule, String pkg, Object targetModule, Class<
                    ?> moduleClass) {
        try {
            Method getPackagesMethod = moduleClass.getMethod("getPackages");
            Set<String> packages = (Set<String>) getPackagesMethod.invoke(sourceModule);

            if (!packages.contains(pkg)) {
                return;
            }

            Method isExportedMethod = moduleClass.getMethod("isExported", String.class, moduleClass);
            boolean isExported = (Boolean) isExportedMethod.invoke(sourceModule, pkg, targetModule);

            if (isExported) {
                return;
            }

            // 方法1: 标准addExports
            try {
                Method addExports = moduleClass.getMethod("addExports", String.class, moduleClass);
                addExports.invoke(sourceModule, pkg, targetModule);
                //LOGGER.info("[RuntimeModuleOpener]   ✔ Exported {} to {}", pkg, getModuleName(targetModule));
                return;
            } catch (Exception e) {
                LOGGER.debug("Standard addExports failed: {}", e.getMessage());
            }

            // 方法2: 内部implAddExports
            try {
                Method implAddExports = moduleClass.getDeclaredMethod("implAddExports", String.class, moduleClass);
                implAddExports.setAccessible(true);
                implAddExports.invoke(sourceModule, pkg, targetModule);
                //LOGGER.info("[RuntimeModuleOpener]   ✔ Exported {} to {} (impl)", pkg, getModuleName(targetModule));
            } catch (Exception e) {
                LOGGER.debug("implAddExports failed: {}", e.getMessage());
            }

        } catch (Exception e) {
            LOGGER.debug("[RuntimeModuleOpener] Could not export package {}: {}", pkg, e.getMessage());
        }
    }

    /** 获取类所在的模块 */
    private static Object getModuleOf(Class<?> clazz) {
        try {
            Method getModule = Class.class.getMethod("getModule");
            Object module = getModule.invoke(clazz);

            Method isNamed = module.getClass().getMethod("isNamed");
            boolean named = (Boolean) isNamed.invoke(module);

            if (named) {
                Method getName = module.getClass().getMethod("getName");
                String name = (String) getName.invoke(module);
                //LOGGER.info("[RuntimeModuleOpener] Found named module: {}", name);
                return module;
            }

            return module;

        } catch (Exception e) {
            LOGGER.debug("Could not get module for {}", clazz.getName());
            return null;
        }
    }

    /** 打开包(允许深度反射) */
    private static void openPackage(Object module, String pkg, Class<?> moduleClass) {
        try {
            Method getPackagesMethod = moduleClass.getMethod("getPackages");
            Set<String> packages = (Set<String>) getPackagesMethod.invoke(module);

            if (!packages.contains(pkg)) {
                return;
            }

            // 尝试多种方法打开包
            try {
                Method implAddOpens = moduleClass.getDeclaredMethod("implAddOpens", String.class);
                implAddOpens.setAccessible(true);
                implAddOpens.invoke(module, pkg);
                //LOGGER.info("[RuntimeModuleOpener]   ✔ Opened: {}", pkg);
                return;
            } catch (NoSuchMethodException e) {
            }

            try {
                Method implAddOpensToAllUnnamed = moduleClass.getDeclaredMethod(
                        "implAddOpensToAllUnnamed", String.class
                );
                implAddOpensToAllUnnamed.setAccessible(true);
                implAddOpensToAllUnnamed.invoke(module, pkg);
                //LOGGER.info("[RuntimeModuleOpener]   ✔ Opened to all unnamed: {}", pkg);
                return;
            } catch (NoSuchMethodException e) {
            }

            try {
                Method addOpens = moduleClass.getMethod("addOpens", String.class, moduleClass);
                Object targetModule = getModuleOf(RuntimeModuleOpener.class);
                addOpens.invoke(module, pkg, targetModule);
                //LOGGER.info("[RuntimeModuleOpener]   ✔ Opened via addOpens: {}", pkg);
            } catch (Exception e) {
                LOGGER.debug("Could not open package {}: {}", pkg, e.getMessage());
            }

        } catch (Exception e) {
            LOGGER.debug("Failed to open package {}: {}", pkg, e.getMessage());
        }
    }

    /** 导出包 */
    private static void exportPackage(Object module, String pkg, Class<?> moduleClass) {
        try {
            Method getPackagesMethod = moduleClass.getMethod("getPackages");
            Set<String> packages = (Set<String>) getPackagesMethod.invoke(module);

            if (!packages.contains(pkg)) {
                return;
            }

            try {
                Method implAddExports = moduleClass.getDeclaredMethod("implAddExports", String.class);
                implAddExports.setAccessible(true);
                implAddExports.invoke(module, pkg);
                //LOGGER.info("[RuntimeModuleOpener]   ✔ Exported: {}", pkg);
                return;
            } catch (NoSuchMethodException e) {
            }

            try {
                Method implAddExportsToAllUnnamed = moduleClass.getDeclaredMethod(
                        "implAddExportsToAllUnnamed", String.class
                );
                implAddExportsToAllUnnamed.setAccessible(true);
                implAddExportsToAllUnnamed.invoke(module, pkg);
                //LOGGER.info("[RuntimeModuleOpener]   ✔ Exported to all unnamed: {}", pkg);
                return;
            } catch (NoSuchMethodException e) {
            }

            try {
                Method addExports = moduleClass.getMethod("addExports", String.class, moduleClass);
                Object targetModule = getModuleOf(RuntimeModuleOpener.class);
                addExports.invoke(module, pkg, targetModule);
                //LOGGER.info("[RuntimeModuleOpener]   ✔ Exported via addExports: {}", pkg);
            } catch (Exception e) {
                LOGGER.debug("Could not export package {}: {}", pkg, e.getMessage());
            }

        } catch (Exception e) {
            LOGGER.debug("Failed to export package {}: {}", pkg, e.getMessage());
        }
    }

    /** 使用Unsafe强制打开模块 */
    private static void tryUnsafeOpen(Object module, String[] packages) {
        try {
            //LOGGER.info("[RuntimeModuleOpener] Attempting Unsafe method...");

            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);

            Class<?> moduleClass = module.getClass();

            try {
                Field openPackagesField = moduleClass.getDeclaredField("openPackages");
                long offset = unsafe.objectFieldOffset(openPackagesField);
                Object openPackages = unsafe.getObject(module, offset);

                if (openPackages instanceof Map) {
                    Map<String, Set<Object>> map = (Map<String, Set<Object>>) openPackages;
                    for (String pkg : packages) {
                        if (!map.containsKey(pkg)) {
                            map.put(pkg, new HashSet<>());
                        }
                    }
                    //LOGGER.info("[RuntimeModuleOpener]   ✔ Used Unsafe to open packages");
                }
            } catch (NoSuchFieldException e) {
                LOGGER.debug("openPackages field not found");
            }

        } catch (Exception e) {
            LOGGER.debug("Unsafe method failed: {}", e.getMessage());
        }
    }

    /** 验证能否访问关键类 */
    private static void verifyAccess() {
        String[] testClasses = {
                RAIN_TOOLS_PACKAGE + ".AnnotatedMixins",
                RAIN_TOOLS_PACKAGE + ".MixinObfuscationProcessorTargets",
                RAIN_TOOLS_PACKAGE + ".MixinObfuscationProcessorInjection",
                RAIN_MIXIN_PACKAGE + ".transformer.MixinTransformer",
                RAIN_MIXIN_PACKAGE + ".Mixin"
        };

        //LOGGER.info("[RuntimeModuleOpener] Verifying access to critical classes:");

        int successCount = 0;
        for (String className : testClasses) {
            try {
                Class<?> clazz = Class.forName(className);
                Method[] methods = clazz.getDeclaredMethods();
                //LOGGER.info("[RuntimeModuleOpener]   ✅ Can access: {}", className);
                successCount++;
            } catch (Exception e) {
                LOGGER.warn("[RuntimeModuleOpener]   ❌ Cannot access: {} - {}",
                        className, e.getMessage());
            }
        }

        //LOGGER.info("[RuntimeModuleOpener] Access verification: {}/{} classes accessible",
         //       successCount, testClasses.length);
    }

    /** 重置状态(用于测试) */
    public static void reset() {
        initialized = false;
        javaBaseOpened = false;
    }
}