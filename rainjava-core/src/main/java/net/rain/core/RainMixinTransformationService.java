package net.rain.core;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Unsafe;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.management.ManagementFactory;
import java.lang.module.Configuration;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.net.URL;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;


@SuppressWarnings("deprecation")
public class RainMixinTransformationService implements ITransformationService {
    
    public static final Logger LOGGER = LoggerFactory.getLogger("RainMixinCoreMod");
    
    // 标志位：Agent是否激活
    private static boolean agentActive = false;
    private static final String OS = System.getProperty("os.name").toLowerCase();
    public static boolean isWindows() { return OS.contains("win"); }
    public static boolean isMac() { return OS.contains("mac"); }
    public static boolean isLinux() { return OS.contains("linux") || OS.contains("unix"); }
    
    static {
        makeMyModLoadable();
        // 自动检测环境并attach Agent
        autoAttachAgent();
    }

    @Override
    public @NotNull String name() {
        return "rainmixin";
    }

    @Override
    public void initialize(IEnvironment environment) {}

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) {
        if (agentActive) {
            LOGGER.info("[RainMixin] Agent active - Mixin patching enabled");
        } else {
            LOGGER.error("[RainMixin] =========================================");
            LOGGER.error("[RainMixin] Agent not active! Mixin patching disabled.");
            LOGGER.error("[RainMixin] Please use JDK or ensure Agent is in resources");
            LOGGER.error("[RainMixin] =========================================");
        }
    }

    @Override
    public @NotNull List<ITransformer> transformers() {
        return List.of();
    }
    
    
    private Optional<URL> locateToolsJarClass(String className) {
        if (className.startsWith("com.sun.tools.attach")) {
            try {
                // 从打包的 tools.jar 中提供类
                String path = className.replace('.', '/') + ".class";
                URL url = getClass().getResource("/META-INF/jarjar/tools-1.0.0.jar/" + path);
                LOGGER.info("成功探查: {}", className);
                return Optional.ofNullable(url);
            } catch (Exception e) {
                LOGGER.error("[RainMixin] Failed to locate class: {}", className, e);
            }
        }
        return Optional.empty();
    }
    
    /**
     * 自动检测环境并attach Agent
     */
    private static void autoAttachAgent() {
        try {
            LOGGER.info("[RainMixin] Detecting Java environment...");
            
            // 尝试加载VirtualMachine类
            Class.forName("com.sun.tools.attach.VirtualMachine");
            LOGGER.info("[RainMixin] VirtualMachine found - JDK environment detected");
            
            // JDK环境：直接attach
            attachWithJDKJava();
            
        } catch (ClassNotFoundException e) {
            // JRE环境：尝试用JDK的java命令启动Agent
            LOGGER.warn("[RainMixin] VirtualMachine not found - JRE environment detected");
            LOGGER.warn("[RainMixin] Attempting to use JDK's java to attach agent...");
            
            try {
                attachWithJDKJava();
            } catch (Exception ex) {
                LOGGER.error("[RainMixin] Failed to attach with JDK", ex);
                agentActive = false;
            }
        } catch (Exception e) {
            LOGGER.error("[RainMixin] Agent attachment failed", e);
            agentActive = false;
        }
    }
    
    /**
     * JDK环境：直接attach
     */
    private static void performAgentAttachment() {
        try {
            LOGGER.info("[RainMixin] Starting agent attachment...");
            
            String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            LOGGER.info("[RainMixin] Current PID: {}", pid);
            
            Path agentJar = extractAgentJar();
            if (agentJar == null) {
                LOGGER.error("[RainMixin] Failed to extract agent JAR");
                agentActive = false;
                return;
            }
            
            LOGGER.info("[RainMixin] Agent JAR extracted to: {}", agentJar);
            
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) unsafeField.get(null);
            
            Field implLookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            MethodHandles.Lookup implLookup = (MethodHandles.Lookup) unsafe.getObject(
                unsafe.staticFieldBase(implLookupField),
                unsafe.staticFieldOffset(implLookupField)
            );
            
            Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
            
            MethodHandle attachHandle = implLookup.findStatic(
                vmClass,
                "attach",
                MethodType.methodType(vmClass, String.class)
            );
            
            Object vm = attachHandle.invoke(pid);
            LOGGER.info("[RainMixin] Attached to VM: {}", vm);
            
            MethodHandle loadAgentHandle = implLookup.findVirtual(
                vmClass,
                "loadAgent",
                MethodType.methodType(void.class, String.class)
            );
            
            loadAgentHandle.invoke(vm, agentJar.toString());
            LOGGER.info("[RainMixin] Agent loaded successfully");
            
            MethodHandle detachHandle = implLookup.findVirtual(
                vmClass,
                "detach",
                MethodType.methodType(void.class)
            );
            
            detachHandle.invoke(vm);
            LOGGER.info("[RainMixin] Detached from VM");
            agentActive = true;
            
        } catch (Throwable e) {
            LOGGER.error("[RainMixin] JDK attach failed", e);
            agentActive = false;
        }
    }
    
    /**
     * JRE环境：使用JDK的java命令启动Agent来attach
     */
    private static void attachWithJDKJava() throws Exception {
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        Path agentJar = extractAgentJar();
        if (agentJar == null) {
            LOGGER.error("[RainMixin] Failed to extract agent JAR");
            return;
        }
        
        String javaHome = System.getProperty("java.home");
        LOGGER.info("[RainMixin] Current java.home: {}", javaHome);
        
        // 尝试定位JDK的java命令
        String javaCommand = findJavaCommand(javaHome);
        if (javaCommand == null) {
            // 尝试从JAVA_HOME定位
            String javaHomeEnv = System.getenv("JAVA_HOME");
            if (javaHomeEnv != null) {
                LOGGER.info("[RainMixin] Trying JAVA_HOME: {}", javaHomeEnv);
                javaCommand = findJavaCommand(javaHomeEnv);
            }
        }
        
        if (javaCommand == null) {
            LOGGER.error("[RainMixin] Could not find JDK java command");
            LOGGER.error("[RainMixin] Please install JDK or set JAVA_HOME environment variable");
            agentActive = false;
            return;
        }
        
        LOGGER.info("[RainMixin] Found JDK java: {}", javaCommand);
        
        // 构建命令: java -jar agent.jar <pid> <agent-jar-path>
        List<String> command = new ArrayList<>();
        command.add(javaCommand);
        command.add("-jar");
        command.add(agentJar.toString());
        command.add(pid);
        command.add(agentJar.toString());
        
        LOGGER.info("[RainMixin] Running attach command: {}", String.join(" ", command));
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        // 读取输出，等待完成
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            LOGGER.info("[RainMixin-Attach] {}", line);
        }
        
        int exitCode = process.waitFor();
        if (exitCode == 0) {
            LOGGER.info("[RainMixin] Agent attached successfully via JDK");
            agentActive = true;
        } else {
            LOGGER.error("[RainMixin] Agent attach process exited with code {}", exitCode);
            agentActive = false;
        }
    }
    
    /**
     * 在不同路径下查找java命令
     */
    private static String findJavaCommand(String javaHome) {
        try {
            // 路径1: java.home/bin/java (JDK)
            File javaBin = new File(javaHome, "bin" + File.separator + "java");
            if (isWindows()) {
                javaBin = new File(javaHome, "bin" + File.separator + "java.exe");
            }
            if (javaBin.exists() && javaBin.isFile()) {
                return javaBin.getAbsolutePath();
            }
            
            // 路径2: java.home/../bin/java (JDK在JRE的父目录)
            File parentJavaBin = new File(new File(javaHome).getParent(), 
                "bin" + File.separator + "java");
            if (isWindows()) {
                parentJavaBin = new File(new File(javaHome).getParent(), 
                    "bin" + File.separator + "java.exe");
            }
            if (parentJavaBin.exists() && parentJavaBin.isFile()) {
                return parentJavaBin.getAbsolutePath();
            }
            
            // 路径3: 检查是否是JDK目录
            File toolsJar = new File(javaHome, "lib" + File.separator + "tools.jar");
            if (toolsJar.exists()) {
                // 这是一个 JDK，使用其 bin/java
                File jdkJava = new File(javaHome, "bin" + File.separator + "java");
                if (isWindows()) {
                    jdkJava = new File(javaHome, "bin" + File.separator + "java.exe");
                }
                if (jdkJava.exists()) {
                    return jdkJava.getAbsolutePath();
                }
            }
            
        } catch (Exception e) {
            LOGGER.debug("[RainMixin] Error finding java command: {}", e.getMessage());
        }
        
        return null;
    }
    
    private static Path extractAgentJar() {
        try {
            InputStream agentStream = getResourceAsStream("/META-INF/agent/rainmixin-agent.jar");
            
            if (agentStream == null) {
                LOGGER.error("[RainMixin] Agent JAR not found in resources");
                return null;
            }
            
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
            Path agentJar = tempDir.resolve("rainmixin-agent-" + System.currentTimeMillis() + ".jar");
            
            try (FileOutputStream fos = new FileOutputStream(agentJar.toFile())) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = agentStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
            
            agentJar.toFile().deleteOnExit();
            LOGGER.info("[RainMixin] Agent JAR extracted to: {}", agentJar);
            
            return agentJar;
            
        } catch (Exception e) {
            LOGGER.error("[RainMixin] Failed to extract agent JAR", e);
            return null;
        }
    }
    
    public static void makeMyModLoadable() {
        try {
            Class<?> modDirTransformerDiscoverer = Class.forName("net.minecraftforge.fml.loading.ModDirTransformerDiscoverer");
            VarHandle foundHandle = MethodHandles.privateLookupIn(modDirTransformerDiscoverer, MethodHandles.lookup())
                .findStaticVarHandle(modDirTransformerDiscoverer, "found", List.class);
            List<?> found = (List<?>) foundHandle.get();
            found.removeIf((namedPath) -> {
                Path[] paths = null;
                try {
                    paths = (Path[])namedPath.getClass().getMethod("paths").invoke(namedPath);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
                return paths[0].toString().contains("rain_java_core");
            });
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }

        try {
            Class<?> launcher = Class.forName("cpw.mods.modlauncher.Launcher");
            Class<?> moduleLayerHandlerClass = Class.forName("cpw.mods.modlauncher.ModuleLayerHandler");
            VarHandle instanceHandle = MethodHandles.privateLookupIn(launcher, MethodHandles.lookup())
                .findStaticVarHandle(launcher, "INSTANCE", launcher);
            Object INSTANCE = instanceHandle.get();
            VarHandle moduleLayerHandlerHandle = MethodHandles.privateLookupIn(launcher, MethodHandles.lookup())
                .findVarHandle(launcher, "moduleLayerHandler", moduleLayerHandlerClass);
            Object moduleLayerHandler = moduleLayerHandlerHandle.get(INSTANCE);
            VarHandle completedLayersHandle = MethodHandles.privateLookupIn(moduleLayerHandlerClass, MethodHandles.lookup())
                .findVarHandle(moduleLayerHandlerClass, "completedLayers", EnumMap.class);
            EnumMap<?, ?> completedLayers = (EnumMap<?, ?>) completedLayersHandle.get(moduleLayerHandler);
            Class<?> layerInfoClass = Class.forName("cpw.mods.modlauncher.ModuleLayerHandler$LayerInfo");
            VarHandle layerHandle = MethodHandles.privateLookupIn(layerInfoClass, MethodHandles.lookup())
                .findVarHandle(layerInfoClass, "layer", ModuleLayer.class);
            
            completedLayers.values().forEach((layerInfo) -> {
                ModuleLayer layer = (ModuleLayer) layerHandle.get(layerInfo);
                Configuration config = layer.configuration();
                String moduleName = RainMixinTransformationService.class.getModule().getName();
                try {
                    Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
                    unsafeField.setAccessible(true);
                    Unsafe unsafe = (Unsafe)unsafeField.get(null);
                    Field nameToModuleField = Configuration.class.getDeclaredField("nameToModule");
                    long nameToModuleOffset = unsafe.objectFieldOffset(nameToModuleField);
                    Field modulesField = Configuration.class.getDeclaredField("modules");
                    long modulesOffset = unsafe.objectFieldOffset(modulesField);
                    Map<String, Object> nameToModule1 = (Map)unsafe.getObject(config, nameToModuleOffset);
                    Set<Object> modules1 = (Set)unsafe.getObject(config, modulesOffset);
                    Map<String, Object> nameToModule2 = new HashMap(nameToModule1);
                    Set<Object> modules2 = new HashSet(modules1);
                    if (nameToModule1 != null && nameToModule1.containsKey(moduleName)) {
                        modules2.remove(nameToModule2.remove(moduleName));
                    }

                    unsafe.putObject(config, nameToModuleOffset, nameToModule2);
                    unsafe.putObject(config, modulesOffset, modules2);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
    
    private static InputStream getResourceAsStream(String resourcePath) {
        // 首先尝试当前类的ClassLoader
        InputStream is = RainMixinTransformationService.class.getResourceAsStream(resourcePath);
        if (is != null) {
            return is;
        }
        
        // 然后尝试去掉开头的/
        String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        is = RainMixinTransformationService.class.getClassLoader().getResourceAsStream(path);
        if (is != null) {
            return is;
        }
        
        // 最后尝试ContextClassLoader
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null) {
            is = contextClassLoader.getResourceAsStream(path);
        }
        
        return is;
    }
}
