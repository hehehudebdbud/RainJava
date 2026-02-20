package net.rain.rainjava.core;

/** 脚本类型枚举 定义了三种不同的脚本加载时机 */
public enum ScriptType {
    /** 服务器端脚本 - 在服务器启动时加载 用于服务器端逻辑、命令注册等 */
    SERVER("server"),

    /** 客户端脚本 - 在客户端设置阶段加载 用于客户端渲染、UI、按键绑定等 */
    CLIENT("client"),

    /** 启动脚本 - 在通用设置阶段加载（只执行一次） 用于注册物品、方块、实体等游戏内容 */
    STARTUP("startup");

    private final String name;

    ScriptType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /** 根据名称查找对应的脚本类型，找不到返回 null */
    public static ScriptType fromName(String name) {
        for (ScriptType t : values()) {
            if (t.getName().equalsIgnoreCase(name)) return t;
        }
        return null;
    }

    @Override
    public String toString() {
        return name;
    }
}