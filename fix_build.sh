# 创建 step_build.sh
cat > step_build.sh << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash

echo "=== 分步构建 ==="

# 步骤1：只下载插件（超时15分钟）
timeout 900 ./gradlew --no-daemon --configure-on-demand tasks 2>&1 | tee step1.log
if [ $? -eq 124 ]; then
    echo "步骤1超时，但可能已经下载了部分依赖"
fi

# 步骤2：下载依赖（超时30分钟）
echo "等待30秒让系统恢复..."
sleep 30
timeout 1800 ./gradlew --no-daemon --configure-on-demand dependencies 2>&1 | tee step2.log
if [ $? -eq 124 ]; then
    echo "步骤2超时，继续下一步"
fi

# 步骤3：编译Java（超时20分钟）
echo "等待30秒让系统恢复..."
sleep 30
timeout 1200 ./gradlew --no-daemon --configure-on-demand compileJava 2>&1 | tee step3.log
if [ $? -ne 0 ]; then
    echo "编译失败，检查日志"
    exit 1
fi

# 步骤4：处理资源（超时10分钟）
echo "等待30秒让系统恢复..."
sleep 30
timeout 600 ./gradlew --no-daemon --configure-on-demand processResources 2>&1 | tee step4.log

# 步骤5：构建JAR（超时15分钟）
echo "等待30秒让系统恢复..."
sleep 30
timeout 900 ./gradlew --no-daemon --configure-on-demand jar 2>&1 | tee step5.log

echo "构建完成！检查 build/libs/ 目录"
EOF

chmod +x step_build.sh