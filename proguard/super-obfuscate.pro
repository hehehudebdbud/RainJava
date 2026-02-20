# ================================================
# 超级激进混淆配置 - 全大写变量名混淆版
# ================================================

# 输入输出配置
-injars @INPUT_JAR@
-outjars @OUTPUT_JAR@

-libraryjars <java.home>/jmods/java.base.jmod(!**.jar;!module-info.class)

# ================================================
# 激进混淆核心设置
# ================================================

-dontshrink
# 启用所有优化
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*,!method/propagation/*,!code/removal/advanced,!code/removal/simple,!code/allocation/variable
-optimizationpasses 10

# ================================================
# 超级视觉混淆词典
# ================================================

-obfuscationdictionary obfuscation-dictionary.txt
-classobfuscationdictionary class-dictionary.txt  
-packageobfuscationdictionary package-dictionary.txt

# ================================================
# 关键修改：移除全大写保护，强制混淆所有字段
# ================================================

# ❌ 删除以下保留规则（这会阻止static final字段被混淆）：
# -keepclassmembers class * {
#     static final ** *;
# }

# ✅ 改为只保留真正必要的静态final字段（如serialVersionUID）
-keepclassmembernames class * {
    static final long serialVersionUID;
    static final java.io.ObjectStreamField[] serialPersistentFields;
}

# ================================================
# 超强力混淆配置
# ================================================

# 激进重载
-overloadaggressively
-useuniqueclassmembernames
-allowaccessmodification
-mergeinterfacesaggressively

# 极致的包名混淆
-repackageclasses 'OoOo0Oo0Oo0Oo0Oo0Oo0Oo0Oo0Oo0Oo0Oo0Oo0Oo0Oo0O'
-flattenpackagehierarchy 'OOoo0Oo0Oo0Oo0Oo0Oo0Oo0Oo0Oo0Oo0Oo0Oo0Oo0Oo0O'

# 字符串混淆
-adaptclassstrings

# 数字混淆
-adaptresourcefilenames **.properties,**.xml
-adaptresourcefilecontents **.properties,**.xml

# ================================================
# 激进属性处理
# ================================================

# 只保留最少的属性
#-keepattributes Exceptions,Signature

# 移除调试信息
-dontpreverify

# ================================================
# 其他激进设置
# ================================================

# 强制混淆所有内容
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers

# 启用所有可能的混淆技术
-ignorewarnings
-mergeinterfacesaggressively

# 激进的内联设置
-optimizations method/inlining/*

# 强制重命名所有可能的标识符
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ================================================
# 日志和输出
# ================================================

-verbose
-printmapping mapping.txt
-printconfiguration used-configuration.txt
-dontnote
-dontwarn

# ================================================
# 额外激进选项
# ================================================

# 混淆枚举（如果有的话）- 保留必要方法但混淆字段名
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    # 不保留字段，允许混淆枚举常量名
}

# 混淆序列化（如果有的话）
-keepclassmembers class * implements java.io.Serializable {
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# 混淆注解处理
-keepattributes *Annotation*

# 强制混淆所有访问修饰符
-allowaccessmodification

# 最大程度的重命名
-useuniqueclassmembernames

# ================================================
# 新增：针对全大写标识符的特殊处理
# ================================================

# 使用自定义词典覆盖默认行为，确保全大写也被混淆
# 确保词典中包含小写和混合大小写名称以替换全大写
-obfuscationdictionary obfuscation-dictionary.txt

# 强制混淆接口字段（public static final）
# 不添加任何keep规则，默认就会混淆

# 如果你需要保留特定全大写常量（如API常量），使用以下格式：
# -keepclassmembernames class com.example.Constants {
#     public static final java.lang.String HEAD;
# }