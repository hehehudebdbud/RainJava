#!/bin/bash
echo "=== Minecraft 模组极强视觉混淆工具 ==="

# 配置 - 使用相对路径
PROGUARD_JAR="lib/proguard.jar"
CONFIG_FILE="super-obfuscate.pro"
INPUT_JAR=""
OUTPUT_JAR=""

# 切换到脚本所在目录
cd "$(dirname "$0")"

# 检查依赖
check_dependencies() {
    if [ ! -f "$PROGUARD_JAR" ]; then
        echo "错误: 未找到 ProGuard JAR 文件: $PROGUARD_JAR"
        exit 1
    fi
    
    if [ ! -f "$CONFIG_FILE" ]; then
        echo "错误: 未找到配置文件: $CONFIG_FILE"
        exit 1
    fi
    
    # 检查词典文件
    for dict in obfuscation-dictionary.txt class-dictionary.txt package-dictionary.txt; do
        if [ ! -f "$dict" ]; then
            echo "错误: 未找到词典文件: $dict"
            echo "请先运行词典生成脚本"
            exit 1
        fi
    done
}

# 构建项目
build_project() {
    echo "步骤1: 构建 Minecraft 模组..."
    cd ..
    #./gradlew build
    cd - > /dev/null
    
    # 在项目根目录查找JAR文件
    INPUT_JAR=$(find ../build/libs -name "*.jar" ! -name "*sources*" ! -name "*dev*" | head -n 1)
    
    if [ -z "$INPUT_JAR" ]; then
        echo "错误: 未找到生成的JAR文件"
        exit 1
    fi
    
    OUTPUT_JAR="../build/obfuscated/$(basename "$INPUT_JAR" .jar)-visual-obf.jar"
    echo "输入文件: $INPUT_JAR"
    echo "输出文件: $OUTPUT_JAR"
}

# 准备配置
prepare_config() {
    echo "步骤2: 准备混淆配置..."
    
    # 创建临时配置文件
    TEMP_CONFIG="temp-visual-config.pro"
    cp "$CONFIG_FILE" "$TEMP_CONFIG"
    
    # 替换占位符
    sed -i "s|@INPUT_JAR@|$INPUT_JAR|g" "$TEMP_CONFIG"
    sed -i "s|@OUTPUT_JAR@|$OUTPUT_JAR|g" "$TEMP_CONFIG"
    
    # 创建输出目录
    mkdir -p ../build/obfuscated
}

# 运行混淆
run_obfuscation() {
    echo "步骤3: 运行极强视觉混淆..."
    echo "使用视觉混淆词典: O/o/0, I/i/1, l/1 等极易混淆字符"
    
    # 使用临时配置运行
    java -jar "$PROGUARD_JAR" @"$TEMP_CONFIG"
    
    local result=$?
    
    # 清理临时文件
    rm -f "$TEMP_CONFIG"
    
    return $result
}

# 验证结果
verify_result() {
    if [ $? -eq 0 ] && [ -f "$OUTPUT_JAR" ]; then
        echo "✓ 极强视觉混淆完成!"
        echo "输出文件: $OUTPUT_JAR"
        echo "文件大小: $(du -h "$OUTPUT_JAR" | cut -f1)"
        
        # 移动映射文件到项目根目录
        if [ -f "mapping.txt" ]; then
            mv mapping.txt ../mapping.txt
            echo "映射文件: ../mapping.txt ($(wc -l < ../mapping.txt) 行)"
            echo "前10个混淆示例:"
            head -10 ../mapping.txt
        fi
        
        if [ -f "seeds.txt" ]; then
            mv seeds.txt ../seeds.txt
            echo "保留的类: ../seeds.txt ($(wc -l < ../seeds.txt) 行)"
        fi
        
        if [ -f "used-configuration.txt" ]; then
            mv used-configuration.txt ../used-configuration.txt
        fi
        
        echo ""
        echo "⚠️  重要提醒:"
        echo "- 保存 ../mapping.txt 文件，这是唯一能理解混淆后代码的文件"
        echo "- 在测试环境中验证模组功能"
        echo "- 混淆后的代码极难反编译和理解"
        
    else
        echo "✗ 混淆失败"
        exit 1
    fi
}

# 主函数
main() {
    check_dependencies
    build_project
    prepare_config
    run_obfuscation
    verify_result
}

main "$@"