# 类型层次信息收集功能

## 功能概述

在求解引用类型的符号变量约束时，现在可以自动收集并发送类型的继承关系信息给大模型（LLM），以帮助 LLM 更好地理解类型约束并生成合适的解。

## 主要组件

### 1. TypeHierarchyInfo 类
位置：`src/main/gov/nasa/jpf/jdart/solvers/llm/TypeHierarchyInfo.java`

这个类负责从 JPF 的 `ClassInfo` 对象中提取类型层次信息，包括：
- 类名和类型签名
- 是否为接口、抽象类或数组
- 父类名称
- 直接实现的接口
- 所有父类（完整继承链）
- 所有接口（包括继承的）

### 2. LLMEnhancedSolverContext 增强
位置：`src/main/gov/nasa/jpf/jdart/solvers/llm/LLMEnhancedSolverContext.java`

在 `buildJsonPayload` 方法中增加了 `collectTypeHierarchyInfo` 调用，会：
1. 遍历当前 valuation 中的所有变量
2. 识别引用类型的变量（非 null 对象引用）
3. 从 JPF 堆中获取对象的 `ElementInfo`
4. 提取对象的 `ClassInfo` 并收集类型层次信息
5. 将类型信息格式化为易读的字符串
6. 添加到发送给 LLM 的 JSON payload 中的 `type_hierarchy` 字段

## 发送给 LLM 的数据格式

```json
{
  "constraints": ["高级约束表达式"],
  "valuation": {
    "variable1": "value1",
    "variable2(ref)": 123
  },
  "type_hierarchy": {
    "variable2": "Type: java.util.ArrayList (signature: Ljava/util/ArrayList;)\n  Extends: java.util.AbstractList\n  Implements: java.util.List, java.util.RandomAccess, java.lang.Cloneable, java.io.Serializable\n  Class hierarchy: java.util.ArrayList -> java.util.AbstractList -> java.util.AbstractCollection -> java.lang.Object\n  All interfaces: java.util.List, java.util.Collection, java.lang.Iterable, java.util.RandomAccess, java.lang.Cloneable, java.io.Serializable"
  },
  "hint": "java-jdart-llm-high-level-constraints"
}
```

## 类型信息示例

对于一个 `ArrayList` 对象，收集到的类型信息如下：

```
Type: java.util.ArrayList (signature: Ljava/util/ArrayList;)
  Extends: java.util.AbstractList
  Implements: java.util.List, java.util.RandomAccess, java.lang.Cloneable, java.io.Serializable
  Class hierarchy: java.util.ArrayList -> java.util.AbstractList -> java.util.AbstractCollection -> java.lang.Object
  All interfaces: java.util.List, java.util.Collection, java.lang.Iterable, java.util.RandomAccess, java.lang.Cloneable, java.io.Serializable
```

## 使用场景

这个功能特别适用于以下场景：

1. **多态约束求解**：当约束涉及接口或抽象类时，LLM 需要知道可能的实现类
2. **类型转换**：理解类型之间的继承关系，帮助 LLM 判断类型转换的合法性
3. **instanceof 检查**：提供完整的类型层次，帮助 LLM 理解 instanceof 约束
4. **方法调用**：了解类实现了哪些接口，有助于理解可以调用哪些方法

## 配置要求

要启用此功能，需要在 JPF 配置文件中设置：

```properties
# 启用引用类型符号参数
jdart.configs.all.symbolic.createRefSymbolicParams=true

# 使用 LLM 增强的求解器
jdart.configs.all.symbolic.dp=llm_enhanced_z3
```

## 实现细节

### 信息收集流程

1. **触发时机**：在 `LLMEnhancedSolverContext.solve()` 方法中，当需要求解高级约束时
2. **数据源**：从 JPF 的运行时堆（Heap）和类加载器（ClassLoaderInfo）获取
3. **提取方法**：使用 JPF 的公共 API，包括：
   - `ClassInfo.getName()`
   - `ClassInfo.getType()`
   - `ClassInfo.getSuperClass()`
   - `ClassInfo.getAllInterfaces()`
   - `ClassInfo.isInterface()`, `isAbstract()`, `isArray()`

### 直接接口推导

由于 `ClassInfo.interfaceNames` 是受保护字段，直接实现的接口是通过以下方式推导：
- 获取当前类的所有接口（包括继承的）
- 获取父类的所有接口
- 两者的差集即为当前类直接实现的接口

## 测试示例

参见 `/jdartTest/src/typeHierarchyTest/` 目录下的测试用例。

## 注意事项

1. 只有在 JPF 执行上下文中（即 `VM.getVM() != null`）才能收集类型信息
2. 只为非 null 的引用类型变量收集类型信息
3. 类型信息会打印到控制台，方便调试
4. 如果没有引用类型变量，`type_hierarchy` 字段不会出现在 payload 中

## 未来改进方向

1. 可以添加字段信息（字段名、类型）
2. 可以添加方法签名信息
3. 可以支持泛型类型参数信息
4. 可以缓存类型信息以提高性能
