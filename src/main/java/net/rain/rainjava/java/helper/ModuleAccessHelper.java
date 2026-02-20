/*
 * This file is part of Mixin, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package net.rain.rainjava.java.helper;
 
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

//模块类访问限制真是死妈了
public class ModuleAccessHelper {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final Logger LOGGER = LogManager.getLogger();
    
    /**
     * 强制模块开放指定包给另一个模块
     */
    public static void forceOpenPackage(Module fromModule, String packageName, Module toModule) {
        try {
            // 检查是否已经开放
            if (fromModule.isOpen(packageName, toModule)) {
                return;
            }
            
            // 使用反射调用内部方法
            Method implAddOpens = Module.class.getDeclaredMethod("implAddOpens", String.class, Module.class);
            implAddOpens.setAccessible(true);
            implAddOpens.invoke(fromModule, packageName, toModule);
        } catch (Exception e) {
            throw new RuntimeException("Failed to open package " + packageName + " from " + 
                fromModule.getName() + " to " + toModule.getName(), e);
        }
    }
    
    /**
     * 添加模块读取权限（原来的 openAccess 方法）
     */
    public static void addModuleReads(Module fromModule, Module toModule) {
        try {
            if (fromModule.canRead(toModule)) {
                return;
            }
            Method implAddReads = Module.class.getDeclaredMethod("implAddReads", Module.class);
            implAddReads.setAccessible(true);
            implAddReads.invoke(fromModule, toModule);
        } catch (Exception e) {
            throw new RuntimeException("Failed to add reads from " + 
                fromModule.getName() + " to " + toModule.getName(), e);
        }
    }
    
    /**
     * 解决 Mixin 的模块访问问题
     */
    public static void fixMixinAccess() {
        try {
            // 获取 java.base 模块
            Module javaBaseModule = ClassLoader.class.getModule();
            
            // 获取 Mixin 模块
            Module mixinModule = null;
            for (Module module : ModuleLayer.boot().modules()) {
                if (module.getName() != null && 
                    module.getName().contains("rain.mixin")) {
                    mixinModule = module;
                    break;
                }
            }
            
            if (mixinModule == null) {
                // 尝试通过类加载器获取
                Class<?> mixinConfigClass = Class.forName("org.spongepowered.rain.asm.mixin.transformer.MixinConfig");
                mixinModule = mixinConfigClass.getModule();
            }
            
            if (mixinModule != null && javaBaseModule != null) {
                // 1. 添加读取权限
                addModuleReads(mixinModule, javaBaseModule);
                
                // 2. 强制开放需要的包
                String[] packagesToOpen = {
                    "java.lang",
                    "java.lang.reflect",
                    "java.util",
                    "java.io",
                    "java.nio",
                    "java.net",
                    "java.security",
                    "sun.nio.ch",
                    "sun.security.util",
                    "jdk.internal.loader"
                };
                
                for (String pkg : packagesToOpen) {
                    forceOpenPackage(javaBaseModule, pkg, mixinModule);
                }
                
                LOGGER.info("Successfully opened java.base packages to mixin module");
            }
        } catch (Exception e) {
            //LOGGER.error("Failed to fix mixin access: " + e.getMessage());
           // e.printStackTrace();
        }
    }
}