package net.rain.rainjava.utils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;

public class PathUtils {
    
    /**
     * 从 Path 中移除开头的 "RainJava/replace/" 部分
     * @param originalPath 原始路径
     * @return 修改后的路径
     */
    public static Path removeRainJavaPrefix(Path originalPath) {
        // 获取路径的所有组成部分
        int nameCount = originalPath.getNameCount();
        
        // 查找 "RainJava" 和 "replace" 的位置
        if (nameCount >= 2) {
            String first = originalPath.getName(0).toString();
            String second = originalPath.getName(1).toString();
            
            if (first.equals("RainJava") && second.equals("replace")) {
                // 跳过前两个部分 (RainJava 和 replace)
                return originalPath.subpath(2, nameCount);
            }
        }
        
        return originalPath;
    }
    
    /**
     * 更灵活的方法：移除指定的前缀路径
     * @param originalPath 原始路径
     * @param prefixToRemove 要移除的前缀（如 "RainJava/replace"）
     * @return 修改后的路径
     */
    public static Path removePathPrefix(Path originalPath, String prefixToRemove) {
        // 将前缀转换为 Path 对象
        Path prefixPath = Paths.get(prefixToRemove);
        
        // 检查原始路径是否以这个前缀开头
        if (originalPath.startsWith(prefixPath)) {
            // 计算要跳过的部分数
            int skipCount = prefixPath.getNameCount();
            return originalPath.subpath(skipCount, originalPath.getNameCount());
        }
        
        return originalPath;
    }
}