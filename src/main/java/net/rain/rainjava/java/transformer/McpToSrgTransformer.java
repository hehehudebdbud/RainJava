package net.rain.rainjava.java.transformer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import net.rain.rainjava.java.utils.MinecraftHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * MCP 到 SRG 名称转换器 - 使用 JavaParser API（增强版）
 * 支持链式调用、new 表达式、内部类等复杂场景
 */
public class McpToSrgTransformer {
    private static final Logger LOGGER = LogManager.getLogger();
    private final JavaParser javaParser;
    
    public McpToSrgTransformer() {
        this.javaParser = new JavaParser();
    }
    
    /**
     * 转换 Java 源代码文件
     */
    public String transformFile(Path sourceFile) throws IOException {
        String sourceCode = Files.readString(sourceFile);
        return transformSource(sourceCode, sourceFile.toString());
    }
    
    /**
     * 转换 Java 源代码字符串
     */
    public String transformSource(String sourceCode, String fileName) {
        try {
            // 解析源代码为 AST
            ParseResult<CompilationUnit> parseResult = javaParser.parse(sourceCode);
            
            if (!parseResult.isSuccessful()) {
                LOGGER.error("Failed to parse {}: {}", fileName, parseResult.getProblems());
                return sourceCode;
            }
            
            CompilationUnit cu = parseResult.getResult().orElse(null);
            if (cu == null) {
                LOGGER.error("Failed to get compilation unit for {}", fileName);
                return sourceCode;
            }
            
            // 应用 MCP -> SRG 转换
            McpToSrgVisitor visitor = new McpToSrgVisitor();
            cu.accept(visitor, null);
            
            if (visitor.getTransformCount() > 0) {
                LOGGER.info("Transformed {} MCP names to SRG in {}", 
                    visitor.getTransformCount(), fileName);
            }
            
            // 返回转换后的代码
            return cu.toString();
            
        } catch (Exception e) {
            LOGGER.error("Error transforming {}: {}", fileName, e.getMessage(), e);
            return sourceCode;
        }
    }
    
    /**
     * AST 访问器 - 遍历并转换 MCP 名称（增强版 + 安全策略）
     */
    private static class McpToSrgVisitor extends ModifierVisitor<Void> {
        private int transformCount = 0;
        
        /**
         * 需要转换的包前缀（白名单）
         * 只有这些包中的类会被转换 MCP -> SRG
         */
        private static final String[] TRANSFORM_PACKAGES = {
            "net.minecraft.",
            "com.mojang.",
            "com.mojang.authlib.",
            "com.mojang.datafixers.",
            "net/minecraft/",
            "com/mojang",
        };
        
        /**
         * 检查类名是否需要转换
         * 只转换 Minecraft 相关的类，避免误转换 Java 标准库、Forge API 等
         */
        private boolean shouldTransform(String className) {
            if (className == null || className.isEmpty()) {
                return false;
            }
            
            // 只转换白名单中的包
            for (String prefix : TRANSFORM_PACKAGES) {
                if (className.startsWith(prefix)) {
                    return true;
                }
            }
            
            return false;
        }
        
        public int getTransformCount() {
            return transformCount;
        }
        
        /**
         * 访问字段访问表达式
         */
        @Override
        public Visitable visit(FieldAccessExpr n, Void arg) {
            super.visit(n, arg);
            
            String fieldName = n.getNameAsString();
            Expression scope = n.getScope();
            
            if (scope != null) {
                String className = resolveClassName(scope);
                
                // 只转换 Minecraft 相关的类
                if (className != null && !className.isEmpty() && shouldTransform(className)) {
                    String srgName = MinecraftHelper.findSrgFieldName(className, fieldName);
                    
                    if (srgName != null && !srgName.equals(fieldName)) {
                        LOGGER.info("Transforming field: {}.{} -> {}", 
                            className, fieldName, srgName);
                        n.setName(srgName);
                        transformCount++;
                    }
                }
            }
            
            return n;
        }
        
        /**
         * 访问方法调用表达式
         */
        @Override
        public Visitable visit(MethodCallExpr n, Void arg) {
            super.visit(n, arg);
            
            String methodName = n.getNameAsString();
            Optional<Expression> scopeOpt = n.getScope();
            
            if (scopeOpt.isPresent()) {
                Expression scope = scopeOpt.get();
                String className = resolveClassName(scope);
                
                // 只转换 Minecraft 相关的类
                if (className != null && !className.isEmpty() && shouldTransform(className)) {
                    String srgName = MinecraftHelper.findSrgMethodName(className, methodName);
                    
                    if (srgName != null && !srgName.equals(methodName)) {
                        LOGGER.info("Transforming method: {}.{}() -> {}()", 
                            className, methodName, srgName);
                        n.setName(srgName);
                        transformCount++;
                    }
                }
            }
            
            return n;
        }
        
        /**
         * 访问名称表达式
         */
        @Override
        public Visitable visit(NameExpr n, Void arg) {
            super.visit(n, arg);
            
            String fieldName = n.getNameAsString();
            Optional<Node> parent = n.getParentNode();
            String className = null;
            
            while (parent.isPresent()) {
                Node node = parent.get();
                if (node instanceof ClassOrInterfaceDeclaration) {
                    ClassOrInterfaceDeclaration classDecl = (ClassOrInterfaceDeclaration) node;
                    className = getFullyQualifiedName(classDecl);
                    break;
                }
                parent = node.getParentNode();
            }
            
            // 只转换 Minecraft 相关的类中的字段
            if (className != null && shouldTransform(className)) {
                String srgName = MinecraftHelper.findSrgFieldName(className, fieldName);
                
                if (srgName != null && !srgName.equals(fieldName)) {
                    LOGGER.info("Transforming field reference: {} -> {} in {}", 
                        fieldName, srgName, className);
                    n.setName(srgName);
                    transformCount++;
                }
            }
            
            return n;
        }
        
        /**
         * 解析表达式的类名（增强版）
         */
        private String resolveClassName(Expression expr) {
            // 情况 1: new 表达式 - 直接获取创建的类型
            if (expr instanceof ObjectCreationExpr) {
                ObjectCreationExpr objCreation = (ObjectCreationExpr) expr;
                String typeName = objCreation.getType().asString();
                return resolveFullClassName(typeName, expr);
            }
            
            // 情况 2: 简单名称表达式（变量名）
            if (expr instanceof NameExpr) {
                NameExpr nameExpr = (NameExpr) expr;
                return resolveVariableType(nameExpr);
            }
            
            // 情况 3: this 表达式
            if (expr instanceof ThisExpr) {
                return getEnclosingClassName(expr);
            }
            
            // 情况 4: 字段访问
            if (expr instanceof FieldAccessExpr) {
                FieldAccessExpr fieldAccess = (FieldAccessExpr) expr;
                return resolveFieldType(fieldAccess);
            }
            
            // 情况 5: 方法调用（返回类型）
            if (expr instanceof MethodCallExpr) {
                MethodCallExpr methodCall = (MethodCallExpr) expr;
                return resolveMethodReturnType(methodCall);
            }
            
            // 情况 6: 类型表达式
            if (expr instanceof ClassExpr) {
                ClassExpr classExpr = (ClassExpr) expr;
                return classExpr.getType().asString();
            }
            
            // 情况 7: Cast 表达式
            if (expr instanceof CastExpr) {
                CastExpr castExpr = (CastExpr) expr;
                return castExpr.getType().asString();
            }
            
            return null;
        }
        
        /**
         * 解析完全限定类名（处理内部类和导入）
         */
        private String resolveFullClassName(String simpleClassName, Node context) {
            // 如果已经是完全限定名
            if (simpleClassName.contains(".")) {
                return simpleClassName;
            }
            
            // 查找编译单元
            Optional<Node> parent = context.getParentNode();
            while (parent.isPresent()) {
                if (parent.get() instanceof CompilationUnit) {
                    CompilationUnit cu = (CompilationUnit) parent.get();
                    
                    // 检查导入语句
                    for (var importDecl : cu.getImports()) {
                        String importName = importDecl.getNameAsString();
                        
                        // 单个类导入
                        if (!importDecl.isAsterisk()) {
                            if (importName.endsWith("." + simpleClassName)) {
                                return importName;
                            }
                            // 内部类导入，如 Item.Properties
                            if (importName.endsWith("$" + simpleClassName)) {
                                return importName.replace("$", ".");
                            }
                        }
                        // 包导入
                        else {
                            String packageName = importName.substring(0, importName.length() - 2);
                            // 尝试常见的类
                            String fullName = packageName + "." + simpleClassName;
                            try {
                                Class.forName(fullName);
                                return fullName;
                            } catch (ClassNotFoundException e) {
                                // 继续尝试
                            }
                        }
                    }
                    
                    // 检查同包的类
                    if (cu.getPackageDeclaration().isPresent()) {
                        String packageName = cu.getPackageDeclaration().get().getNameAsString();
                        String fullName = packageName + "." + simpleClassName;
                        try {
                            Class.forName(fullName);
                            return fullName;
                        } catch (ClassNotFoundException e) {
                            // 不是同包类
                        }
                    }
                    
                    break;
                }
                parent = parent.get().getParentNode();
            }
            
            // 尝试 java.lang 包
            try {
                Class.forName("java.lang." + simpleClassName);
                return "java.lang." + simpleClassName;
            } catch (ClassNotFoundException e) {
                // 不是 java.lang 的类
            }
            
            // 返回简单名称（可能需要进一步处理）
            return simpleClassName;
        }
        
        /**
         * 解析变量的类型
         */
        private String resolveVariableType(NameExpr nameExpr) {
            String varName = nameExpr.getNameAsString();
            
            Optional<Node> parent = nameExpr.getParentNode();
            while (parent.isPresent()) {
                Node node = parent.get();
                
                // 在方法中查找局部变量
                if (node instanceof MethodDeclaration) {
                    MethodDeclaration method = (MethodDeclaration) node;
                    
                    // 检查方法参数
                    for (var param : method.getParameters()) {
                        if (param.getNameAsString().equals(varName)) {
                            return resolveFullClassName(param.getType().asString(), nameExpr);
                        }
                    }
                    
                    // 检查方法体中的变量声明
                    if (method.getBody().isPresent()) {
                        String varType = findVariableInStatements(
                            method.getBody().get().getStatements(), 
                            varName, 
                            nameExpr
                        );
                        if (varType != null) {
                            return varType;
                        }
                    }
                }
                
                // 在类中查找字段
                if (node instanceof ClassOrInterfaceDeclaration) {
                    ClassOrInterfaceDeclaration classDecl = (ClassOrInterfaceDeclaration) node;
                    for (FieldDeclaration field : classDecl.getFields()) {
                        for (VariableDeclarator var : field.getVariables()) {
                            if (var.getNameAsString().equals(varName)) {
                                return resolveFullClassName(var.getType().asString(), nameExpr);
                            }
                        }
                    }
                }
                
                parent = node.getParentNode();
            }
            
            return null;
        }
        
        /**
         * 在语句列表中查找变量声明
         */
        private String findVariableInStatements(
                com.github.javaparser.ast.NodeList<com.github.javaparser.ast.stmt.Statement> statements,
                String varName,
                Node context) {
            
            for (var stmt : statements) {
                // 表达式语句
                if (stmt.isExpressionStmt()) {
                    var exprStmt = stmt.asExpressionStmt();
                    if (exprStmt.getExpression().isVariableDeclarationExpr()) {
                        var varDecl = exprStmt.getExpression().asVariableDeclarationExpr();
                        for (VariableDeclarator var : varDecl.getVariables()) {
                            if (var.getNameAsString().equals(varName)) {
                                return resolveFullClassName(var.getType().asString(), context);
                            }
                        }
                    }
                }
                
                // For 语句
                if (stmt.isForStmt()) {
                    var forStmt = stmt.asForStmt();
                    for (var expr : forStmt.getInitialization()) {
                        if (expr.isVariableDeclarationExpr()) {
                            var varDecl = expr.asVariableDeclarationExpr();
                            for (VariableDeclarator var : varDecl.getVariables()) {
                                if (var.getNameAsString().equals(varName)) {
                                    return resolveFullClassName(var.getType().asString(), context);
                                }
                            }
                        }
                    }
                }
                
                // Try-with-resources
                if (stmt.isTryStmt()) {
                    var tryStmt = stmt.asTryStmt();
                    for (var resource : tryStmt.getResources()) {
                        if (resource.isVariableDeclarationExpr()) {
                            var varDecl = resource.asVariableDeclarationExpr();
                            for (VariableDeclarator var : varDecl.getVariables()) {
                                if (var.getNameAsString().equals(varName)) {
                                    return resolveFullClassName(var.getType().asString(), context);
                                }
                            }
                        }
                    }
                }
            }
            
            return null;
        }
        
        /**
         * 获取包围的类名
         */
        private String getEnclosingClassName(Node node) {
            Optional<Node> parent = node.getParentNode();
            while (parent.isPresent()) {
                if (parent.get() instanceof ClassOrInterfaceDeclaration) {
                    ClassOrInterfaceDeclaration classDecl = (ClassOrInterfaceDeclaration) parent.get();
                    return getFullyQualifiedName(classDecl);
                }
                parent = parent.get().getParentNode();
            }
            return null;
        }
        
        /**
         * 获取完全限定类名
         */
        private String getFullyQualifiedName(ClassOrInterfaceDeclaration classDecl) {
            String packageName = "";
            Optional<Node> parent = classDecl.getParentNode();
            
            while (parent.isPresent()) {
                if (parent.get() instanceof CompilationUnit) {
                    CompilationUnit cu = (CompilationUnit) parent.get();
                    if (cu.getPackageDeclaration().isPresent()) {
                        packageName = cu.getPackageDeclaration().get().getNameAsString();
                    }
                    break;
                }
                parent = parent.get().getParentNode();
            }
            
            if (!packageName.isEmpty()) {
                return packageName + "." + classDecl.getNameAsString();
            }
            return classDecl.getNameAsString();
        }
        
        /**
         * 解析字段的类型
         */
        private String resolveFieldType(FieldAccessExpr fieldAccess) {
            Expression scope = fieldAccess.getScope();
            if (scope != null) {
                String baseClassName = resolveClassName(scope);
                if (baseClassName != null) {
                    String fieldName = fieldAccess.getNameAsString();
                    
                    // 尝试通过反射获取字段类型
                    try {
                        Class<?> clazz = Class.forName(baseClassName);
                        java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
                        return field.getType().getName();
                    } catch (Exception e) {
                        // 无法通过反射获取
                    }
                }
            }
            return null;
        }
        
        /**
         * 解析方法的返回类型（增强版）
         */
        private String resolveMethodReturnType(MethodCallExpr methodCall) {
            Optional<Expression> scopeOpt = methodCall.getScope();
            if (scopeOpt.isPresent()) {
                Expression scope = scopeOpt.get();
                String className = resolveClassName(scope);
                
                if (className != null) {
                    String methodName = methodCall.getNameAsString();
                    
                    // 尝试通过反射获取方法返回类型
                    try {
                        Class<?> clazz = Class.forName(className);
                        
                        // 查找匹配的方法（按参数数量匹配）
                        int argCount = methodCall.getArguments().size();
                        for (java.lang.reflect.Method method : clazz.getMethods()) {
                            if (method.getName().equals(methodName) && 
                                method.getParameterCount() == argCount) {
                                return method.getReturnType().getName();
                            }
                        }
                        
                        // 如果没找到精确匹配，尝试找同名方法
                        for (java.lang.reflect.Method method : clazz.getMethods()) {
                            if (method.getName().equals(methodName)) {
                                return method.getReturnType().getName();
                            }
                        }
                    } catch (ClassNotFoundException e) {
                        LOGGER.info("Could not resolve class: {}", className);
                    }
                }
            }
            return null;
        }
    }
}
