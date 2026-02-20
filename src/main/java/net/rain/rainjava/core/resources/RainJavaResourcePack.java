package net.rain.rainjava.resources;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;
import net.rain.rainjava.RainJava;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * RainJava 资源包实现
 * 支持从文件系统加载 assets（客户端资源）和 data（数据包）
 */
public class RainJavaResourcePack implements PackResources {
    private final Path rootPath;
    private final PackType packType;
    private final Set<String> namespaces;
    
    public RainJavaResourcePack(Path rootPath, PackType packType) {
        this.rootPath = rootPath;
        this.packType = packType;
        this.namespaces = new HashSet<>();
        
        // 扫描命名空间
        scanNamespaces();
    }
    
    /**
     * 扫描可用的命名空间（文件夹名称）
     */
    private void scanNamespaces() {
        if (!Files.exists(rootPath)) {
            return;
        }
        
        try (Stream<Path> paths = Files.list(rootPath)) {
            paths.filter(Files::isDirectory)
                 .map(path -> path.getFileName().toString())
                 .forEach(namespaces::add);
        } catch (IOException e) {
            RainJava.LOGGER.error("Failed to scan namespaces in: {}", rootPath, e);
        }
        
        if (!namespaces.isEmpty()) {
            RainJava.LOGGER.info("Found namespaces in RainJava {}: {}", 
                packType == PackType.CLIENT_RESOURCES ? "assets" : "data", 
                namespaces);
        }
    }
    
    /**
     * 获取根目录资源（如 pack.png, pack.mcmeta）
     */
    @Nullable
    @Override
    public IoSupplier<InputStream> getRootResource(String... paths) {
        if (paths.length == 0) {
            return null;
        }
        
        String fileName = String.join("/", paths);
        
        // 尝试从父目录读取
        Path file = rootPath.getParent().resolve(fileName);
        if (Files.exists(file)) {
            return () -> Files.newInputStream(file);
        }
        
        // 如果请求 pack.mcmeta，返回默认配置
        if ("pack.mcmeta".equals(fileName)) {
            String meta = """
                {
                    "pack": {
                        "pack_format": 15,
                        "description": "RainJava Dynamic Resources"
                    }
                }
                """;
            return () -> new java.io.ByteArrayInputStream(meta.getBytes());
        }
        
        return null;
    }
    
    /**
     * 获取资源文件
     * 路径格式：assets/命名空间/路径 或 data/命名空间/路径
     */
    @Nullable
    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (type != this.packType) {
            return null;
        }
        
        // 构建文件路径: rootPath/namespace/path
        Path file = rootPath.resolve(location.getNamespace())
                           .resolve(location.getPath());
        
        if (Files.exists(file) && Files.isRegularFile(file)) {
            RainJava.LOGGER.debug("Loading resource: {}", file);
            return () -> Files.newInputStream(file);
        }
        
        return null;
    }
    
    /**
     * 列出指定路径下的所有资源
     */
    @Override
    public void listResources(PackType type, String namespace, String path, 
                             PackResources.ResourceOutput resourceOutput) {
        if (type != this.packType) {
            return;
        }
        
        Path namespacePath = rootPath.resolve(namespace).resolve(path);
        if (!Files.exists(namespacePath)) {
            return;
        }
        
        try (Stream<Path> paths = Files.walk(namespacePath)) {
            paths.filter(Files::isRegularFile).forEach(file -> {
                try {
                    Path relative = namespacePath.relativize(file);
                    String resourcePath = path + "/" + relative.toString().replace('\\', '/');
                    ResourceLocation location = new ResourceLocation(namespace, resourcePath);
                    
                    resourceOutput.accept(location, () -> Files.newInputStream(file));
                } catch (Exception e) {
                    RainJava.LOGGER.error("Error visiting resource: {}", file, e);
                }
            });
        } catch (IOException e) {
            RainJava.LOGGER.error("Failed to list resources in: {}", namespacePath, e);
        }
    }
    
    /**
     * 获取所有可用的命名空间
     */
    @Override
    public Set<String> getNamespaces(PackType type) {
        if (type != this.packType) {
            return Set.of();
        }
        return new HashSet<>(namespaces);
    }
    
    /**
     * 获取 pack.mcmeta 元数据
     */
    @Nullable
    @Override
    public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) throws IOException {
        try {
            if (deserializer.getMetadataSectionName().equals("pack")) {
                JsonObject pack = new JsonObject();
                pack.addProperty("pack_format", 15);
                pack.addProperty("description", "RainJava Dynamic Resources");
                
                return deserializer.fromJson(pack);
            }
        } catch (Exception e) {
            RainJava.LOGGER.error("Error reading pack metadata", e);
        }
        return null;
    }
    
    /**
     * 资源包 ID
     */
    @Override
    public String packId() {
        return "rainjava_" + (packType == PackType.CLIENT_RESOURCES ? "assets" : "data");
    }
    
    /**
     * 关闭资源包（不需要执行任何操作）
     */
    @Override
    public void close() {
        // 文件系统资源不需要关闭
    }
}