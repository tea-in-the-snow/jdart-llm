# LLM 服务类型层次信息集成

## 概述

LLM 求解服务现在支持接收和处理 Java 类型的层次结构信息，以便在求解引用类型约束时提供更好的上下文。

## API 更新

### 请求格式

POST `/solve` 端点现在接受一个新的可选字段 `type_hierarchy`：

```json
{
  "constraints": ["约束列表"],
  "valuation": {"变量名": "值"},
  "type_hierarchy": {
    "变量名": "类型层次描述"
  },
  "max_tokens": 512,
  "temperature": 0.0
}
```

### type_hierarchy 字段

`type_hierarchy` 是一个字典，键为变量名，值为该变量类型的详细描述字符串，包括：
- 类名和类型签名
- 父类
- 实现的接口
- 完整的类继承链
- 所有接口（包括继承的）

### 示例请求

```json
{
  "constraints": [
    "list.<ref> instanceof Ljava/util/List;",
    "list.<ref> != null"
  ],
  "valuation": {
    "list.<ref>": 123
  },
  "type_hierarchy": {
    "list": "Type: java.util.ArrayList (signature: Ljava/util/ArrayList;)\n  Extends: java.util.AbstractList\n  Implements: java.util.List, java.util.RandomAccess, java.lang.Cloneable, java.io.Serializable\n  Class hierarchy: java.util.ArrayList -> java.util.AbstractList -> java.util.AbstractCollection -> java.lang.Object\n  All interfaces: java.util.List, java.util.Collection, java.lang.Iterable, java.util.RandomAccess, java.lang.Cloneable, java.io.Serializable"
  }
}
```

## 实现细节

### 修改的文件

1. **app.py**
   - `SolveRequest` 类新增 `type_hierarchy` 字段
   - 提示构建逻辑中添加类型层次信息块
   - 更新系统指令以说明如何使用类型信息

### 提示构建

当 `type_hierarchy` 字段存在时，服务会构建一个专门的信息块并插入到发送给 LLM 的提示中：

```
Type Hierarchy Information:

Variable: list
Type: java.util.ArrayList (signature: Ljava/util/ArrayList;)
  Extends: java.util.AbstractList
  Implements: java.util.List, java.util.RandomAccess, java.lang.Cloneable, java.io.Serializable
  Class hierarchy: java.util.ArrayList -> java.util.AbstractList -> java.util.AbstractCollection -> java.lang.Object
  All interfaces: java.util.List, java.util.Collection, java.lang.Iterable, java.util.RandomAccess, java.lang.Cloneable, java.io.Serializable

Constraints:
- list.<ref> instanceof Ljava/util/List;
- list.<ref> != null

Base valuation (may be empty):
list.<ref> = 123
```

### LLM 使用指南

系统指令已更新，明确告知 LLM：
- 当提供 type_hierarchy 信息时，使用它来理解继承关系
- 利用类型信息判断类型约束的可满足性
- 根据类型层次选择合适的类型赋值

## 测试

使用提供的测试脚本验证功能：

```bash
cd llm_service
python3 test_type_hierarchy.py
```

测试脚本包含两个测试：
1. **带类型层次信息的测试**：验证新功能正常工作
2. **不带类型层次信息的测试**：验证向后兼容性

## 向后兼容性

`type_hierarchy` 字段是可选的。不提供此字段时，服务的行为与之前完全相同，确保向后兼容。

## 与 Java 端的集成

Java 端的 `LLMEnhancedSolverContext` 类会自动：
1. 收集引用类型变量的类型层次信息
2. 在 JSON payload 中添加 `type_hierarchy` 字段
3. 发送到此 LLM 服务

整个流程是自动的，用户无需手动操作。

## 日志记录

类型层次信息会被包含在日志记录中，方便调试和审计：
- 请求日志包含 `type_hierarchy` 字段
- 可以在日志文件中查看完整的类型信息

## 性能考虑

- 类型信息仅在需要时收集（即存在引用类型变量时）
- 信息以易读的文本格式传递，LLM 可以直接理解
- 如果没有引用类型变量，不会产生额外开销

## 故障排除

### 类型信息未显示在提示中
- 检查 Java 端是否启用了 `createRefSymbolicParams=true`
- 验证变量是否为引用类型（非 null 对象引用）
- 查看 Java 端的控制台输出，确认类型信息已收集

### LLM 未正确使用类型信息
- 检查系统指令是否正确更新
- 验证类型信息格式是否清晰易读
- 可能需要调整提示或增加示例

## 未来改进

可能的增强方向：
- 添加字段信息（字段名、类型）
- 添加方法签名信息
- 支持泛型类型参数
- 添加类型兼容性检查辅助功能
