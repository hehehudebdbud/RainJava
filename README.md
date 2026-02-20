# RainMixin - Forge 1.20.1 CoreMod

使用 Java Agent 技术在运行时修改 Mixin 类的字节码。

## 快速开始

⚠️ **重要**: 你需要将 `MixinInfoInjector.class` 文件放到：
```
rainjava-agent/src/main/resources/META-INF/class/MixinInfoInjector.class
```

## 构建

```bash
./gradlew build
```

## 使用

将 `rainjava-core/build/libs/rainjava-core-1.0.0.jar` 放入 Minecraft 的 `mods` 文件夹。

## 要求

- JDK 8+ (必须是 JDK，不能是 JRE)
- Forge 1.20.1

## 许可证

根据你的需求选择。
