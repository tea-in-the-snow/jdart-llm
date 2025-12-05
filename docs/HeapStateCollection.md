# Heap State Collection for LLM Solver

## 概述

为了帮助 LLM 更好地理解和求解高层次约束，我们实现了**堆状态收集（Heap State Collection）**功能。该功能在求解时自动收集当前执行路径相关的对象子图，并将其作为额外上下文信息提供给 LLM。

## 核心思想

🎯 **仅提供与当前路径相关（reachable from path variables）的对象子图**

通过从 API 输入参数和局部变量开始做**可达性分析（reachability analysis）**，只收集那些会影响路径条件的对象，而不是整个 Java 堆。

## 实现架构

### 1. HeapStateCollector 类

新增的 `HeapStateCollector` 类负责执行堆可达性分析：

**核心功能：**
- ✅ 从 root 引用（API 参数、局部变量、符号变量）开始
- ✅ 使用 BFS 遍历可达对象
- ✅ 支持深度限制（默认 10 层）
- ✅ 支持对象数量限制（默认 100 个）
- ✅ 过滤无关字段（modCount, size, capacity 等）
- ✅ 保留别名信息（aliasing）

**配置选项：**
```java
HeapStateCollector.Config config = new HeapStateCollector.Config()
    .maxDepth(15)              // 最大遍历深度
    .maxObjects(200)           // 最大对象数量
    .addIrrelevantField("modCount");  // 添加要过滤的字段
```

### 2. LLMSolverClient 增强

修改了 `LLMSolverClient` 以支持发送堆状态信息：

**新增方法：**
```java
public LLMSolverResponse solve(
    List<Expression<Boolean>> hlExpressions, 
    Valuation val, 
    JsonObject heapState  // 新增参数
) throws IOException
```

**JSON Payload 格式：**
```json
{
  "constraints": ["constraint1", "constraint2", ...],
  "valuation": {"var1": value1, "var2": value2, ...},
  "heap_state": {
    "aliases": {
      "head": 466,
      "slow": 469,
      "fast": 486
    },
    "objects": {
      "466": {
        "class": "Node",
        "fields": {
          "next": 469,
          "val": 0
        }
      },
      "469": {
        "class": "Node",
        "fields": {
          "next": 486,
          "val": 0
        }
      },
      "486": {
        "class": "Node",
        "fields": {
          "next": 487
        }
      }
    }
  },
  "hint": "java-jdart-llm-high-level-constraints"
}
```

### 3. LLMEnhancedSolverContext 集成

在 `LLMEnhancedSolverContext.solve()` 方法中自动收集堆状态：

```java
// 从当前线程收集堆状态
ThreadInfo ti = VM.getVM().getCurrentThread();
JsonObject heapState = heapCollector.collectHeapState(ti, val);

// 发送给 LLM solver
LLMSolverResponse response = llmClient.solve(hlExpressions, val, heapState);
```

## 可达性分析算法

### Step 1: 收集 Root 引用

从以下位置收集起始引用：
1. **当前栈帧的局部变量**
2. **Valuation 中的符号变量值**（如果是引用类型）

### Step 2: BFS 遍历

```
Queue<Integer> worklist = 根引用集合
Map<Integer, Integer> refToDepth = 空映射

while (worklist 非空 && 对象数 < maxObjects):
    ref = worklist.poll()
    depth = refToDepth[ref]
    
    if depth >= maxDepth:
        continue
    
    获取对象 ei = heap.get(ref)
    
    遍历 ei 的所有引用字段:
        if 字段名不在过滤列表中 && 子引用未访问:
            将子引用加入 worklist
            记录子引用深度 = depth + 1
```

### Step 3: 构建输出

输出包含两部分：
1. **aliases**: 变量名 → 对象引用的映射
2. **objects**: 对象引用 → 对象描述的映射

## 示例用例：链表环检测

假设我们正在分析 `detectCycle(ListNode head)` 方法：

**输入代码：**
```java
ListNode slow = head;
ListNode fast = head.next;
while (fast != null && fast.next != null) {
    if (slow == fast) return true;  // 环检测
    slow = slow.next;
    fast = fast.next.next;
}
```

**收集的堆状态：**
```json
{
  "aliases": {
    "head": 466,
    "slow": 469,
    "fast": 486
  },
  "objects": {
    "466": {"class": "ListNode", "fields": {"next": 469, "val": 1}},
    "469": {"class": "ListNode", "fields": {"next": 486, "val": 2}},
    "486": {"class": "ListNode", "fields": {"next": 487, "val": 3}},
    "487": {"class": "ListNode", "fields": {"next": 469, "val": 4}}
  }
}
```

通过这个信息，LLM 可以看到：
- `head` 指向对象 466
- `slow` 指向对象 469
- `fast` 指向对象 486
- 对象 487 的 `next` 指向 469，形成了环！

## 优化策略

### 1. 深度限制（Depth Bounding）
防止在无限长的数据结构（如长链表）中过度展开：
```java
Config config = new Config().maxDepth(10);
```

### 2. 字段过滤（Field Relevance Filtering）
过滤掉不影响路径条件的字段：
```java
Config config = new Config()
    .addIrrelevantField("modCount")
    .addIrrelevantField("capacity")
    .addIrrelevantField("hash");
```

### 3. 对象数量限制
防止内存溢出和 LLM 上下文超限：
```java
Config config = new Config().maxObjects(100);
```

### 4. 别名保留（Aliasing Information）
保留变量之间的引用关系，帮助 LLM 理解对象共享：
```json
{
  "aliases": {
    "slow": 469,
    "fast": 486,
    "head": 466
  }
}
```

## 配置选项

### 环境变量

无需额外配置，堆状态收集会自动启用。可以通过以下环境变量配置 LLM solver：

- `LLM_SOLVER_URL`: LLM solver 服务的 URL（默认: `http://127.0.0.1:8000/solve`）
- `LLM_SOLVER_TIMEOUT`: 请求超时时间（秒，默认: 60）

### 代码配置

如需自定义堆状态收集行为：

```java
HeapStateCollector.Config config = new HeapStateCollector.Config()
    .maxDepth(15)           // 增加遍历深度
    .maxObjects(200)        // 增加对象数量限制
    .addIrrelevantField("customField");  // 添加自定义过滤字段

HeapStateCollector collector = new HeapStateCollector(config);
```

## 性能考虑

1. **最小化开销**：只在有高层约束时才收集堆状态
2. **智能限制**：通过深度和数量限制防止过度遍历
3. **选择性收集**：只收集可达对象，忽略无关部分
4. **容错设计**：堆状态收集失败不会影响求解流程

## 未来增强

可能的改进方向：
- [ ] 支持自定义 root 选择策略
- [ ] 添加对象类型过滤（只收集特定类型）
- [ ] 实现增量式堆状态收集
- [ ] 添加堆状态缓存机制
- [ ] 支持用户自定义字段重要性分析

## 相关文件

- `HeapStateCollector.java`: 堆状态收集器实现
- `LLMSolverClient.java`: LLM solver 客户端（已增强）
- `LLMEnhancedSolverContext.java`: 求解上下文（已集成堆状态收集）

## 参考

该实现遵循了符号执行中的可达性分析最佳实践，确保只向 LLM 提供与当前路径相关的必要信息。
