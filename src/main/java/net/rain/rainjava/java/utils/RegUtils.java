// ==================== RegUtil.java (增强版 - 支持完全自定义注册) ====================
package net.rain.rainjava.java.util;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.eventbus.api.IEventBus;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * RainJava 通用注册工具类
 * 自动导入，无需手动import即可使用
 */
public class RegUtils {
    
    // 存储不同mod的注册器
    private static final Map<String, ModRegistries> MOD_REGISTRIES = new HashMap<>();
    
    /**
     * 初始化mod的注册系统
     * @param modId mod的ID
     * @param modEventBus mod的事件总线
     */
    public static void init(String modId, IEventBus modEventBus) {
        if (!MOD_REGISTRIES.containsKey(modId)) {
            ModRegistries registries = new ModRegistries(modId, modEventBus);
            MOD_REGISTRIES.put(modId, registries);
        }
    }
    
    /**
     * 获取指定mod的注册器
     */
    private static ModRegistries getRegistries(String modId) {
        ModRegistries registries = MOD_REGISTRIES.get(modId);
        if (registries == null) {
            throw new IllegalStateException("Mod '" + modId + "' 未初始化! 请先调用 RegUtil.init(modId, eventBus)");
        }
        return registries;
    }
    
    // ==================== 获取 DeferredRegister ====================
    
    /**
     * 获取方块注册器
     */
    public static DeferredRegister<Block> getBlockRegister(String modId) {
        return getRegistries(modId).blocks;
    }
    
    /**
     * 获取物品注册器
     */
    public static DeferredRegister<Item> getItemRegister(String modId) {
        return getRegistries(modId).items;
    }
    
    /**
     * 获取实体注册器
     */
    public static DeferredRegister<EntityType<?>> getEntityRegister(String modId) {
        return getRegistries(modId).entities;
    }
    
    /**
     * 创建自定义注册器
     */
    public static <T> DeferredRegister<T> createRegister(String modId, IForgeRegistry<T> registry, IEventBus eventBus) {
        DeferredRegister<T> register = DeferredRegister.create(registry, modId);
        register.register(eventBus);
        return register;
    }
    
    // ==================== 完全自定义注册方法 ====================
    
    /**
     * 通用自定义注册方法 - 使用 Supplier
     * @param register 注册器
     * @param id 注册ID
     * @param supplier 对象供应器
     * @return 注册对象
     */
    public static <T> RegistryObject<T> registerCustom(DeferredRegister<T> register, String id, Supplier<T> supplier) {
        return register.register(id, supplier);
    }
    
    /**
     * 自定义注册 - 直接传入已构造的对象
     * @param register 注册器
     * @param id 注册ID
     * @param object 要注册的对象
     */
    public static <T> RegistryObject<T> registerCustom(DeferredRegister<T> register, String id, T object) {
        return register.register(id, () -> object);
    }
    
    /**
     * 自定义注册 - 使用反射自动构造（无参构造）
     * @param register 注册器
     * @param id 注册ID
     * @param clazz 要注册的类
     */
    public static <T> RegistryObject<T> registerCustom(DeferredRegister<T> register, String id, Class<? extends T> clazz) {
        return register.register(id, () -> {
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to create instance of " + clazz.getName(), e);
            }
        });
    }
    
    /**
     * 自定义注册 - 使用反射自动构造（带参数）
     * @param register 注册器
     * @param id 注册ID
     * @param clazz 要注册的类
     * @param args 构造函数参数
     */
    public static <T> RegistryObject<T> registerCustom(DeferredRegister<T> register, String id, Class<? extends T> clazz, Object... args) {
        return register.register(id, () -> {
            try {
                // 查找匹配的构造函数
                Constructor<? extends T> constructor = findMatchingConstructor(clazz, args);
                if (constructor == null) {
                    throw new NoSuchMethodException("No matching constructor found for " + clazz.getName());
                }
                constructor.setAccessible(true);
                return constructor.newInstance(args);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create instance of " + clazz.getName(), e);
            }
        });
    }
    
    /**
     * 自定义注册 - 方便的重载版本（自动获取注册器）
     * 适用于 Block
     */
    public static RegistryObject<Block> registerCustomBlock(String modId, String id, Supplier<Block> supplier) {
        return getBlockRegister(modId).register(id, supplier);
    }
    
    /**
     * 自定义注册 - 方便的重载版本（自动获取注册器）
     * 适用于 Item
     */
    public static RegistryObject<Item> registerCustomItem(String modId, String id, Supplier<Item> supplier) {
        return getItemRegister(modId).register(id, supplier);
    }
    
    /**
     * 自定义注册 - 方便的重载版本（自动获取注册器）
     * 适用于 EntityType
     */
    public static RegistryObject<EntityType<?>> registerCustomEntity(String modId, String id, Supplier<EntityType<?>> supplier) {
        return getEntityRegister(modId).register(id, supplier);
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 查找匹配的构造函数
     */
    @SuppressWarnings("unchecked")
    private static <T> Constructor<? extends T> findMatchingConstructor(Class<? extends T> clazz, Object... args) {
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        
        // 获取参数类型
        Class<?>[] argTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            argTypes[i] = args[i] != null ? args[i].getClass() : null;
        }
        
        // 查找精确匹配
        for (Constructor<?> constructor : constructors) {
            Class<?>[] paramTypes = constructor.getParameterTypes();
            if (paramTypes.length == args.length) {
                boolean match = true;
                for (int i = 0; i < paramTypes.length; i++) {
                    if (argTypes[i] != null && !isAssignable(paramTypes[i], argTypes[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return (Constructor<? extends T>) constructor;
                }
            }
        }
        
        return null;
    }
    
    /**
     * 检查类型是否可赋值（支持基本类型）
     */
    private static boolean isAssignable(Class<?> target, Class<?> source) {
        if (target.isAssignableFrom(source)) {
            return true;
        }
        
        // 处理基本类型
        if (target.isPrimitive()) {
            if (target == int.class && source == Integer.class) return true;
            if (target == long.class && source == Long.class) return true;
            if (target == float.class && source == Float.class) return true;
            if (target == double.class && source == Double.class) return true;
            if (target == boolean.class && source == Boolean.class) return true;
            if (target == byte.class && source == Byte.class) return true;
            if (target == short.class && source == Short.class) return true;
            if (target == char.class && source == Character.class) return true;
        }
        
        return false;
    }
    
    // ==================== 原有的便捷方法（保留） ====================
    
    /**
     * 注册一个简单方块
     */
    public static RegistryObject<Block> block(String modId, String name, BlockBehaviour.Properties properties) {
        return getRegistries(modId).blocks.register(name, () -> new Block(properties));
    }
    
    /**
     * 注册一个简单方块（使用默认石头属性）
     */
    public static RegistryObject<Block> block(String modId, String name) {
        return block(modId, name, BlockBehaviour.Properties.copy(Blocks.STONE).strength(1.5F, 6.0F));
    }
    
    /**
     * 注册自定义方块
     */
    public static <T extends Block> RegistryObject<T> block(String modId, String name, Supplier<T> blockSupplier) {
        return getRegistries(modId).blocks.register(name, blockSupplier);
    }
    
    /**
     * 注册方块并自动创建对应的物品
     */
    public static RegistryObject<Block> blockWithItem(String modId, String name, BlockBehaviour.Properties properties) {
        RegistryObject<Block> block = block(modId, name, properties);
        blockItem(modId, name, block);
        return block;
    }
    
    /**
     * 注册方块并自动创建对应的物品（默认属性）
     */
    public static RegistryObject<Block> blockWithItem(String modId, String name) {
        RegistryObject<Block> block = block(modId, name);
        blockItem(modId, name, block);
        return block;
    }
    
    /**
     * 注册一个简单物品
     */
    public static RegistryObject<Item> item(String modId, String name, Item.Properties properties) {
        return getRegistries(modId).items.register(name, () -> new Item(properties));
    }
    
    /**
     * 注册一个简单物品（默认属性）
     */
    public static RegistryObject<Item> item(String modId, String name) {
        return item(modId, name, new Item.Properties());
    }
    
    /**
     * 注册自定义物品
     */
    public static <T extends Item> RegistryObject<T> item(String modId, String name, Supplier<T> itemSupplier) {
        return getRegistries(modId).items.register(name, itemSupplier);
    }
    
    /**
     * 为方块注册对应的物品
     */
    public static RegistryObject<Item> blockItem(String modId, String name, RegistryObject<Block> block) {
        return getRegistries(modId).items.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }
    
    /**
     * 注册实体类型
     */
    public static <T extends net.minecraft.world.entity.Entity> RegistryObject<EntityType<T>> entity(
            String modId, 
            String name, 
            EntityType.Builder<T> builder) {
        return getRegistries(modId).entities.register(name, () -> builder.build(name));
    }
    
    // ==================== 方便的快捷方法 ====================
    
    /**
     * 创建方块属性构建器 - 石头材质
     */
    public static BlockBehaviour.Properties stone() {
        return BlockBehaviour.Properties.copy(Blocks.STONE).strength(1.5F, 6.0F);
    }
    
    
    /**
     * 创建物品属性构建器
     */
    public static Item.Properties itemProps() {
        return new Item.Properties();
    }
    
    /**
     * 创建物品属性构建器（带堆叠数量）
     */
    public static Item.Properties itemProps(int stackSize) {
        return new Item.Properties().stacksTo(stackSize);
    }
    
    /**
     * 复制方块属性
     */
    public static BlockBehaviour.Properties copy(Block block) {
        return BlockBehaviour.Properties.copy(block);
    }
    
    /**
     * 获取所有已注册mod的ID
     */
    public static String[] getRegisteredMods() {
        return MOD_REGISTRIES.keySet().toArray(new String[0]);
    }
    
    // ==================== 内部类：存储每个mod的注册器 ====================
    
    private static class ModRegistries {
        final DeferredRegister<Block> blocks;
        final DeferredRegister<Item> items;
        final DeferredRegister<EntityType<?>> entities;
        final Map<String, DeferredRegister<?>> customRegisters;
        
        ModRegistries(String modId, IEventBus eventBus) {
            this.blocks = DeferredRegister.create(ForgeRegistries.BLOCKS, modId);
            this.items = DeferredRegister.create(ForgeRegistries.ITEMS, modId);
            this.entities = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, modId);
            this.customRegisters = new HashMap<>();
            
            // 注册到事件总线
            this.blocks.register(eventBus);
            this.items.register(eventBus);
            this.entities.register(eventBus);
        }
    }
}