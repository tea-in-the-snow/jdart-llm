# 参数静态类型作为隐式约束的实现

## 概述

本文档描述了如何将方法输入参数的静态类型作为隐式约束传递给LLM求解器。这对于多态推理至关重要,因为它让LLM知道参数的声明类型,从而可以正确处理运行时类型必须是声明类型的子类型这一约束。

## 问题背景

在多态场景中,方法参数的声明类型(静态类型)是一个重要的约束:
- 如果方法签名是 `void process(Node node)`,那么实际传入的对象的运行时类型必须是 `Node` 或其子类型
- 这是一个隐式的类型约束,即使在显式约束中没有提到
- LLM需要知道这个信息才能正确推理多态场景

## 实现方案

### 1. Java端 - 收集参数类型信息

#### 1.1 在 `ConcolicMethodExplorer` 中添加方法

新增了 `getParameterTypeConstraints()` 方法来收集参数的静态类型信息:

```java
/**
 * Get parameter type constraints as implicit constraints for the LLM solver.
 * Returns a map from parameter names to their static type names (e.g., "node" -> "ListNode").
 * This provides the LLM with information about the declared types of method parameters,
 * which is crucial for polymorphic reasoning.
 * 
 * @return Map from symbolic parameter names (without "(ref)" suffix) to their static types
 */
public Map<String, String> getParameterTypeConstraints() {
    Map<String, String> typeConstraints = new HashMap<>();
    
    if (methodInfo == null) {
      return typeConstraints;
    }
    
    List<ParamConfig> pconfig = methodConfig.getParams();
    String[] argTypeNames = methodInfo.getArgumentTypeNames();
    
    // Handle 'this' parameter for non-static methods
    if (!methodInfo.isStatic()) {
      String thisType = methodInfo.getClassName();
      typeConstraints.put("this", thisType);
    }
    
    // Handle method parameters
    for (int i = 0; i < pconfig.size() && i < argTypeNames.length; i++) {
      ParamConfig pc = pconfig.get(i);
      String paramName = pc.getName();
      
      // Only include symbolic parameters (non-null names)
      if (paramName != null) {
        String typeName = argTypeNames[i];
        typeConstraints.put(paramName, typeName);
      }
    }
    
    return typeConstraints;
}
```

这个方法:
- 对于非静态方法,包含 `this` 参数及其类型
- 遍历所有符号化参数,记录参数名和其声明的静态类型
- 返回格式: `{"node": "ListNode", "comparator": "Comparator"}`

#### 1.2 在 `LLMEnhancedSolverContext` 中传递参数类型约束

修改 `solve()` 方法以获取并传递参数类型约束:

```java
@Override
public Result solve(Valuation val) {
    // ... 现有代码 ...
    
    try {
      // Collect heap state and parameter type constraints from current execution context
      JsonObject heapState = null;
      Map<String, String> parameterTypeConstraints = new HashMap<>();
      
      try {
        ThreadInfo ti = VM.getVM().getCurrentThread();
        if (ti != null) {
          // Get parameter type constraints from the current analysis
          gov.nasa.jpf.jdart.ConcolicMethodExplorer currentAnalysis = 
              gov.nasa.jpf.jdart.ConcolicMethodExplorer.getCurrentAnalysis(ti);
          if (currentAnalysis != null) {
            parameterTypeConstraints = currentAnalysis.getParameterTypeConstraints();
            logger.finer("Collected parameter type constraints: " + parameterTypeConstraints);
          }
          
          // ... 收集堆状态 ...
        }
      } catch (Exception e) {
        logger.warning("Failed to collect heap state or parameter types: " + e.getMessage());
      }

      // Send request to LLM solver with parameter type constraints
      LLMSolverResponse response = llmClient.solve(hlExpressions, heapState, parameterTypeConstraints);
      
      // ... 处理响应 ...
    }
}
```

#### 1.3 在 `LLMSolverClient` 中构造JSON payload

修改 `solve()` 和 `buildJsonPayload()` 方法以包含参数类型约束:

```java
public LLMSolverResponse solve(List<Expression<Boolean>> hlExpressions, JsonObject heapState, 
                               java.util.Map<String, String> parameterTypeConstraints) throws IOException {
    String payload = buildJsonPayload(hlExpressions, heapState, parameterTypeConstraints);
    // ... 发送请求和处理响应 ...
}

private String buildJsonPayload(List<Expression<Boolean>> hlExpressions, JsonObject heapState,
                                 java.util.Map<String, String> parameterTypeConstraints) {
    JsonObject payload = new JsonObject();

    // Build constraints array
    JsonArray constraintsArray = new JsonArray();
    for (Expression<Boolean> expr : hlExpressions) {
      constraintsArray.add(new JsonPrimitive(expr.toString()));
    }
    payload.add("constraints", constraintsArray);

    // Add heap state if available
    if (heapState != null && heapState.entrySet().size() > 0) {
      payload.add("heap_state", heapState);
    }

    // Add parameter type constraints if available
    if (parameterTypeConstraints != null && !parameterTypeConstraints.isEmpty()) {
      JsonObject paramTypesJson = new JsonObject();
      for (java.util.Map.Entry<String, String> entry : parameterTypeConstraints.entrySet()) {
        paramTypesJson.addProperty(entry.getKey(), entry.getValue());
      }
      payload.add("parameter_type_constraints", paramTypesJson);
    }

    // Add optional hint field
    payload.addProperty("hint", "java-jdart-llm-high-level-constraints");

    return gson.toJson(payload);
}
```

JSON payload格式:
```json
{
  "constraints": ["node(ref) != null", "..."],
  "heap_state": { ... },
  "parameter_type_constraints": {
    "node": "ListNode",
    "comparator": "Comparator"
  },
  "hint": "java-jdart-llm-high-level-constraints"
}
```

### 2. Python端 - 处理参数类型约束

#### 2.1 在 `app.py` 中接收参数

修改 `SolveRequest` 模型和 `/solve` 端点:

```python
class SolveRequest(BaseModel):
    constraints: List[str]
    valuation: Optional[Dict[str, Any]] = None
    type_hierarchy: Optional[Dict[str, str]] = None
    heap_state: Optional[Dict[str, Any]] = None
    parameter_type_constraints: Optional[Dict[str, str]] = None  # 新增
    max_tokens: Optional[int] = 512
    temperature: Optional[float] = 0.0

@app.post("/solve")
async def solve(req: SolveRequest):
    # ... 
    response_data = orchestrator.solve(
        constraints=req.constraints,
        type_hierarchy=req.type_hierarchy,
        heap_state=req.heap_state,
        parameter_type_constraints=req.parameter_type_constraints,  # 传递
        context=ctx_content,
    )
    # ...
```

#### 2.2 在 `MultiAgentOrchestrator` 中传递

修改 `solve()` 方法签名和调用:

```python
def solve(
    self,
    constraints: List[str],
    type_hierarchy: Optional[Dict[str, str]] = None,
    heap_state: Optional[Dict[str, Any]] = None,
    parameter_type_constraints: Optional[Dict[str, str]] = None,  # 新增
    context: str = "",
) -> Dict[str, Any]:
    # ...
    if iteration == 1:
        solver_output, solver_output_raw, solver_log = self.solver.solve(
            constraints=constraints,
            type_hierarchy=type_hierarchy,
            heap_state=heap_state,
            parameter_type_constraints=parameter_type_constraints,  # 传递
            context=context,
        )
    else:
        solver_output, solver_output_raw, refiner_log = self.refiner.refine(
            constraints=constraints,
            solver_output_raw=solver_output_raw,
            error_report=error_report,
            type_hierarchy=type_hierarchy,
            heap_state=heap_state,
            parameter_type_constraints=parameter_type_constraints,  # 传递
            context=context,
        )
    # ...
```

#### 2.3 在 `SolverAgent` 和 `RefinerAgent` 中使用

在两个agent中都添加参数类型约束信息到prompt中:

```python
def solve(
    self,
    constraints: List[str],
    type_hierarchy: Optional[Dict[str, str]] = None,
    heap_state: Optional[Dict[str, Any]] = None,
    parameter_type_constraints: Optional[Dict[str, str]] = None,  # 新增
    context: str = "",
) -> Tuple[Optional[Dict], str, Dict[str, Any]]:
    # ...
    
    # Build parameter type constraints block (implicit constraints)
    param_type_block = ""
    if parameter_type_constraints:
        param_type_block = "Parameter Type Constraints (Implicit):\n"
        param_type_block += "These are the declared static types of method parameters. "
        param_type_block += "The actual runtime type must be a subtype of the declared type.\n\n"
        for param_name, declared_type in parameter_type_constraints.items():
            param_type_block += f"  {param_name}: declared type is {declared_type}\n"
        param_type_block += "\n"
        param_type_block += "IMPORTANT: When generating the valuation, ensure that:\n"
        param_type_block += "1. Each parameter's actual type is compatible with (subtype of) its declared type\n"
        param_type_block += "2. For reference parameters like 'node(ref)', use the JVM type format (e.g., 'LListNode;')\n"
        param_type_block += "3. The type in the valuation entry must match or be a subtype of the declared type\n\n"
    
    # ...
    
    human_prompt = (
        f"{context_block}"
        f"{param_type_block}"  # 包含在prompt中
        f"{type_hierarchy_block}"
        f"{heap_state_block}"
        f"Constraints:\n{constraints_block}\n\n"
        "Please reason through the constraints and provide your answer in JSON format."
    )
    # ...
```

这样,LLM在求解时会看到类似这样的信息:

```
Parameter Type Constraints (Implicit):
These are the declared static types of method parameters. The actual runtime type must be a subtype of the declared type.

  node: declared type is ListNode
  comparator: declared type is Comparator

IMPORTANT: When generating the valuation, ensure that:
1. Each parameter's actual type is compatible with (subtype of) its declared type
2. For reference parameters like 'node(ref)', use the JVM type format (e.g., 'LListNode;')
3. The type in the valuation entry must match or be a subtype of the declared type
```

## 工作流程

1. **收集阶段** (`ConcolicMethodExplorer`):
   - 方法初始化时,从 `MethodInfo` 获取参数类型名称
   - 存储参数名到静态类型的映射

2. **传递阶段** (`LLMEnhancedSolverContext` → `LLMSolverClient`):
   - 求解时,从当前分析上下文获取参数类型约束
   - 将其与约束和堆状态一起序列化为JSON
   - 通过HTTP请求发送给LLM服务

3. **使用阶段** (Python LLM服务):
   - 接收 `parameter_type_constraints` 字段
   - 在solver和refiner的prompt中包含这些隐式约束
   - LLM在生成解时考虑类型兼容性

## 优势

1. **显式化隐式约束**: 将参数的静态类型这一隐式约束变为显式信息传递给LLM

2. **改善多态推理**: LLM现在知道 "如果方法参数声明为Node,那么实际对象必须是Node或其子类型"

3. **减少类型错误**: 减少LLM生成不兼容类型的valuation的情况

4. **向后兼容**: 所有修改都保持了向后兼容性,旧代码仍然可以工作(只是不传递参数类型约束)

## 示例

假设有方法:
```java
public void process(Node node, Comparator comp) {
    // ...
}
```

生成的 `parameter_type_constraints`:
```json
{
  "node": "Node",
  "comp": "Comparator"
}
```

如果约束中有 `node(ref).getClass() == DerivedNode`,LLM现在知道:
- `node` 的声明类型是 `Node`
- 实际类型是 `DerivedNode`
- `DerivedNode` 必须是 `Node` 的子类型

## 相关文件

### Java端
- `src/main/gov/nasa/jpf/jdart/ConcolicMethodExplorer.java`
  - 新增 `getParameterTypeConstraints()` 方法
  
- `src/main/gov/nasa/jpf/jdart/solvers/llm/LLMEnhancedSolverContext.java`
  - 修改 `solve()` 方法以获取和传递参数类型约束
  
- `src/main/gov/nasa/jpf/jdart/solvers/llm/LLMSolverClient.java`
  - 修改 `solve()` 和 `buildJsonPayload()` 方法以支持参数类型约束

### Python端
- `llm_service/app.py`
  - 修改 `SolveRequest` 模型和 `/solve` 端点
  
- `llm_service/agents/orchestrator.py`
  - 修改 `solve()` 方法以传递参数类型约束
  
- `llm_service/agents/solver_agent.py`
  - 修改 `solve()` 方法以在prompt中包含参数类型约束
  
- `llm_service/agents/refiner_agent.py`
  - 修改 `refine()` 方法以在prompt中包含参数类型约束

## 测试建议

1. **基本功能测试**: 确保参数类型信息被正确收集和传递
2. **多态场景测试**: 使用多态方法测试LLM是否能正确推理子类型关系
3. **向后兼容测试**: 确保没有参数类型约束的情况下系统仍然正常工作
4. **边界情况测试**: 测试静态方法(没有this)、无参方法等边界情况
