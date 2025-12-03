package gov.nasa.jpf.jdart.solvers.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Gson;

import java.net.URL;
import java.net.HttpURLConnection;
import java.io.IOException;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import gov.nasa.jpf.constraints.api.ConstraintSolver.Result;
import gov.nasa.jpf.constraints.api.Expression;
import gov.nasa.jpf.constraints.api.SolverContext;
import gov.nasa.jpf.constraints.api.Valuation;
import gov.nasa.jpf.constraints.api.ValuationEntry;
import gov.nasa.jpf.constraints.api.Variable;
import gov.nasa.jpf.constraints.util.ExpressionUtil;
import gov.nasa.jpf.jdart.ConcolicMethodExplorer;
import gov.nasa.jpf.vm.ClassInfo;
import gov.nasa.jpf.vm.ClassInfoException;
import gov.nasa.jpf.vm.ClassLoaderInfo;
import gov.nasa.jpf.vm.ElementInfo;
import gov.nasa.jpf.vm.Heap;
import gov.nasa.jpf.vm.ThreadInfo;
import gov.nasa.jpf.vm.VM;

import com.google.gson.JsonElement;

public class LLMEnhancedSolverContext extends SolverContext {

  private final SolverContext baseSolverContext;
  private static final Gson gson = new Gson();

  /**
   * Stack of high-level constraints per push/pop scope. Each push() creates a
   * new, empty list on the stack. pop(n) removes the top n scopes.
   */
  private final Deque<List<Expression<Boolean>>> highLevelStack = new ArrayDeque<>();
  private final Deque<Map<String, Variable<?>>> hlFreeVarsStack = new ArrayDeque<Map<String, Variable<?>>>();

  public LLMEnhancedSolverContext(SolverContext baseSolverContext) {
    this.baseSolverContext = baseSolverContext;
    // initialize base scope for high-level constraints
    // this.highLevelStack.push(new ArrayList<>());
    this.hlFreeVarsStack.push(new HashMap<String, Variable<?>>());
  }

  @Override
  public void push() {
    baseSolverContext.push();
    highLevelStack.push(new ArrayList<>());
    Map<String, Variable<?>> fvMap = hlFreeVarsStack.peek();
    hlFreeVarsStack.push(new HashMap<String, Variable<?>>(fvMap));
  }

  @Override
  public void pop(int n) {
    baseSolverContext.pop(n);
    for (int i = 0; i < n; i++) {
      // for (int j = 0; j < highLevelStack.peek().size(); j++) {
      //   System.out.println("high-level constraint " + j + ": " + highLevelStack.peek().get(j));
      // }
      highLevelStack.pop();
      hlFreeVarsStack.pop();
    }
  }

  @Override
  public void add(List<Expression<Boolean>> expressions) {
    if (expressions == null || expressions.isEmpty()) {
      return;
    }

    List<Expression<Boolean>> normal = new ArrayList<>();
    List<Expression<Boolean>> high = new ArrayList<>();

    Map<String, Variable<?>> fvMap = hlFreeVarsStack.peek();

    for (Expression<Boolean> e : expressions) {
      if (e != null && containsHighLevel(e)) {
        high.add(e);
        Set<Variable<?>> fvs = ExpressionUtil.freeVariables(e);
        for (Variable<?> v : fvs) {
          fvMap.put(v.getName(), v);
        }
      } else {
        normal.add(e);
      }
      // Set<Variable<?>> fvs = ExpressionUtil.freeVariables(e);
      // for (Variable<?> v : fvs) {
      // fvMap.put(v.getName(), v);
      // }

    }

    // Forward normal constraints to the base solver immediately
    if (!normal.isEmpty()) {
      baseSolverContext.add(normal);
    }

    // Store high-level constraints in the current high-level scope
    if (!high.isEmpty()) {
      List<Expression<Boolean>> current = highLevelStack.peek();
      // if (current == null) {
      // current = new ArrayList<>();
      // highLevelStack.push(current);
      // }
      current.addAll(high);
    }
  }

  @Override
  public void dispose() {
    highLevelStack.clear();
    baseSolverContext.dispose();
    hlFreeVarsStack.clear();
  }

  @Override
  public Result solve(Valuation val) {
    // If there are no high-level constraints in any scope, delegate to base solver.
    boolean hasHighLevel = highLevelStack.stream().anyMatch(list -> !list.isEmpty());
    if (!hasHighLevel) {
      return baseSolverContext.solve(val);
    }

    // First, solve normal (non-high-level) constraints using base solver
    Result baseResult = baseSolverContext.solve(val);
    if (baseResult != Result.SAT) {
      System.out.println("**********************************************************");
      System.out.println("Base constraints are UNSAT, returning UNSAT");
      System.out.println("**********************************************************");
      return baseResult;
    }

    List<Expression<Boolean>> hlExpressions = highLevelStack.stream()
        .flatMap(List::stream)
        .collect(Collectors.toList());

    System.out.println("----------------------------------------------------------");
    System.out.println("Solving with " + hlExpressions.size() + " high-level constraints");
    System.out.println("hlExpressions: " + hlExpressions);
    System.out.println("----------------------------------------------------------");

    if (hlExpressions.isEmpty()) {
      return baseResult;
    }

    // Get solver configuration
    SolverConfig config = getSolverConfiguration();

    // Build JSON payload
    String payload = buildJsonPayload(hlExpressions, val);

    try {
      // Send request to LLM solver
      String responseBody = sendLlmRequest(config.url, payload, config.timeoutSeconds);
      if (responseBody == null) {
        return Result.DONT_KNOW;
      }

      // Parse response and update valuation
      return parseLlmResponse(responseBody, val);
    } catch (IOException e) {
      // If the LLM service is unreachable, fall back to base solver's result.
      System.err.println("LLM solver call failed: " + e.getMessage());
      return baseResult;
    }
  }

  /**
   * Configuration for LLM solver connection.
   */
  private static class SolverConfig {
    final String url;
    final int timeoutSeconds;

    SolverConfig(String url, int timeoutSeconds) {
      this.url = url;
      this.timeoutSeconds = timeoutSeconds;
    }
  }

  /**
   * Get solver configuration from environment variables.
   * The service URL can be configured via LLM_SOLVER_URL (default:
   * http://127.0.0.1:8000/solve).
   * The request timeout can be configured via LLM_SOLVER_TIMEOUT (default: 10s).
   */
  private SolverConfig getSolverConfiguration() {
    String solverUrl = System.getenv("LLM_SOLVER_URL");
    if (solverUrl == null || solverUrl.isEmpty()) {
      solverUrl = "http://127.0.0.1:8000/solve";
    }

    int timeoutSeconds = 10;
    String timeoutEnv = System.getenv("LLM_SOLVER_TIMEOUT");
    if (timeoutEnv != null && !timeoutEnv.isEmpty()) {
      try {
        timeoutSeconds = Integer.parseInt(timeoutEnv);
      } catch (NumberFormatException nfe) {
        System.err
            .println("Invalid LLM_SOLVER_TIMEOUT value '" + timeoutEnv + "', using default " + timeoutSeconds + "s");
      }
    }

    return new SolverConfig(solverUrl, timeoutSeconds);
  }

  /**
   * Build JSON payload from high-level expressions and valuation.
   * Uses Gson for proper JSON serialization with automatic escaping.
   */
  private String buildJsonPayload(List<Expression<Boolean>> hlExpressions, Valuation val) {
    JsonObject payload = new JsonObject();

    // Build constraints array
    JsonArray constraintsArray = new JsonArray();
    for (Expression<Boolean> expr : hlExpressions) {
      constraintsArray.add(new JsonPrimitive(expr.toString()));
    }
    payload.add("constraints", constraintsArray);

    // Build valuation object
    if (val == null) {
      payload.add("valuation", null);
    } else {
      JsonObject valuationObj = new JsonObject();
      for (ValuationEntry<?> entry : val.entries()) {
        String varName = entry.getVariable().getName();
        Object value = entry.getValue();
        
        // Convert value to appropriate JsonElement
        if (value == null) {
          valuationObj.add(varName, null);
        } else if (value instanceof String) {
          valuationObj.addProperty(varName, (String) value);
        } else if (value instanceof Number) {
          // Handle different number types
          if (value instanceof Integer) {
            valuationObj.addProperty(varName, (Integer) value);
          } else if (value instanceof Long) {
            valuationObj.addProperty(varName, (Long) value);
          } else if (value instanceof Double) {
            valuationObj.addProperty(varName, (Double) value);
          } else if (value instanceof Float) {
            valuationObj.addProperty(varName, (Float) value);
          } else {
            // For other number types, convert to string
            valuationObj.addProperty(varName, value.toString());
          }
        } else if (value instanceof Boolean) {
          valuationObj.addProperty(varName, (Boolean) value);
        } else {
          // For other types, convert to string
          valuationObj.addProperty(varName, value.toString());
        }
      }
      payload.add("valuation", valuationObj);
    }

    // Collect and add type hierarchy information for reference type symbolic variables
    JsonObject typeHierarchyObj = collectTypeHierarchyInfo(val);
    if (typeHierarchyObj != null && typeHierarchyObj.entrySet().size() > 0) {
      payload.add("type_hierarchy", typeHierarchyObj);
    }

    // Add optional hint field
    payload.addProperty("hint", "java-jdart-llm-high-level-constraints");

    return gson.toJson(payload);
  }

  /**
   * Collect type hierarchy information for reference type symbolic variables.
   * This extracts class inheritance relationships, implemented interfaces, etc.
   * from the JPF ClassInfo objects for variables that reference objects.
   * 
   * @param val the current valuation containing variable values
   * @return JsonObject mapping variable names to their type hierarchy information
   */
  private JsonObject collectTypeHierarchyInfo(Valuation val) {
    JsonObject typeHierarchyObj = new JsonObject();
    
    if (val == null) {
      return typeHierarchyObj;
    }
    
    VM vm = VM.getVM();
    if (vm == null) {
      // Not in JPF execution context
      return typeHierarchyObj;
    }
    
    Heap heap = vm.getHeap();
    
    // Iterate through all variables in the valuation
    for (ValuationEntry<?> entry : val.entries()) {
      String varName = entry.getVariable().getName();
      Object value = entry.getValue();
      
      // Check if this is a reference type variable (integer reference in JPF)
      if (value instanceof Integer) {
        int objRef = (Integer) value;
        
        // Skip null references
        if (objRef == 0) {
          continue;
        }
        
        // Get the object from the heap
        ElementInfo ei = heap.get(objRef);
        if (ei == null) {
          continue;
        }
        
        // Get the ClassInfo for this object
        ClassInfo ci = ei.getClassInfo();
        
        // Extract type hierarchy information
        TypeHierarchyInfo typeInfo = TypeHierarchyInfo.extractFrom(ci);
        if (typeInfo != null) {
          // Convert to descriptive string format
          String typeDesc = typeInfo.toDescriptiveString();
          typeHierarchyObj.addProperty(varName, typeDesc);
          
          System.out.println("Collected type hierarchy for " + varName + ":");
          System.out.println(typeDesc);
        }
      }
    }
    
    return typeHierarchyObj;
  }

  /**
   * Send HTTP POST request to LLM solver and return response body.
   * Returns null if request fails or response is invalid.
   */
  private String sendLlmRequest(String solverUrl, String payload, int timeoutSeconds) throws IOException {
    URL url = new URL(solverUrl);
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("POST");
    connection.setRequestProperty("Content-Type", "application/json");
    connection.setDoOutput(true);
    connection.setConnectTimeout(timeoutSeconds * 1000);
    connection.setReadTimeout(timeoutSeconds * 1000);

    // Write the payload
    try (OutputStream os = connection.getOutputStream()) {
      byte[] input = payload.getBytes("utf-8");
      os.write(input, 0, input.length);
    }

    int statusCode = connection.getResponseCode();
    if (statusCode / 100 != 2) {
      System.err.println("LLM solver returned non-2xx status: " + statusCode);
      connection.disconnect();
      return null;
    }

    // Read the response
    StringBuilder responseBody = new StringBuilder();
    try (BufferedReader br = new BufferedReader(
        new InputStreamReader(connection.getInputStream(), "utf-8"))) {
      String responseLine;
      while ((responseLine = br.readLine()) != null) {
        responseBody.append(responseLine.trim());
      }
    }
    connection.disconnect();

    String body = responseBody.toString();
    if (body == null || body.isEmpty()) {
      System.err.println("LLM solver returned empty body");
      return null;
    }

    return body;
  }

  /**
   * Parse LLM solver response and update valuation if SAT.
   * Returns the appropriate Result based on the response.
   */
  private Result parseLlmResponse(String body, Valuation val) {
    try {
      JsonObject jsonObject = new JsonParser().parse(body).getAsJsonObject();
      
      // Get result field
      if (!jsonObject.has("result")) {
        System.err.println("LLM solver response missing 'result' field, body: " + body);
        return Result.DONT_KNOW;
      }
      
      String resultStr = jsonObject.get("result").getAsString().toUpperCase();
      
      if ("SAT".equals(resultStr)) {
        // Parse the valuation array from the body
        JsonArray llmValuationArray = null;
        if (jsonObject.has("valuation") && !jsonObject.get("valuation").isJsonNull()) {
          llmValuationArray = jsonObject.getAsJsonArray("valuation");
        }

        System.out.println("\n===================================================");
        // Update the valuation with the valuation array
        if (val != null && llmValuationArray != null) {
          updateValuationFromLlmResponse(llmValuationArray, val);
        }
        System.out.println("=================================================\n");

        return Result.SAT;
      } else if ("UNSAT".equals(resultStr)) {
        return Result.UNSAT;
      } else if ("UNKNOWN".equals(resultStr) || "DONT_KNOW".equals(resultStr)) {
        return Result.DONT_KNOW;
      } else {
        System.err.println("LLM solver returned unknown result value: " + resultStr);
        return Result.DONT_KNOW;
      }
    } catch (Exception e) {
      System.err.println("Failed to parse LLM solver response: " + e.getMessage());
      System.err.println("Response body: " + body);
      e.printStackTrace();
      return Result.DONT_KNOW;
    }
  }

  /**
   * Update valuation from LLM response valuation array.
   */
  private void updateValuationFromLlmResponse(JsonArray llmValuationArray, Valuation val) {
    // Build a map from variable name to Variable object
    Map<String, Variable<?>> varNameToVar = buildVariableNameMap();

    // Iterate through each object in the valuation array
    for (int i = 0; i < llmValuationArray.size(); i++) {
      JsonObject valuationObj = llmValuationArray.get(i).getAsJsonObject();

      // Iterate through each key-value pair in the object
      for (Map.Entry<String, JsonElement> entry : valuationObj.entrySet()) {
        String varName = entry.getKey();
        System.out.println("varName: " + varName);
        JsonElement valueElement = entry.getValue();

        // Find the corresponding Variable object
        Variable<?> var = varNameToVar.get(varName);
        if (var != null) {
          updateVariableValue(var, varName, valueElement, val);
        } else {
          System.out.println("Warning: Variable " + varName + " not found in current valuation, skipping");
        }
      }
    }
  }

  /**
   * Build a map from variable name to Variable object.
   * Includes free variables from high-level constraints.
   */
  private Map<String, Variable<?>> buildVariableNameMap() {
    Map<String, Variable<?>> varNameToVar = new HashMap<String, Variable<?>>();

    // Add free variables from high-level constraints (in case they're not in the
    // valuation yet)
    Map<String, Variable<?>> hlFreeVars = hlFreeVarsStack.peek();
    if (hlFreeVars != null) {
      varNameToVar.putAll(hlFreeVars);
    }

    return varNameToVar;
  }

  /**
   * Update a single variable's value in the valuation from LLM response.
   */
  private void updateVariableValue(Variable<?> var, String varName, JsonElement valueElement, Valuation val) {
    // Extract the value as a string
    String valueStr;
    if (valueElement.isJsonNull()) {
      valueStr = "null";
    } else if (valueElement.isJsonPrimitive()) {
      valueStr = valueElement.getAsString();
    } else {
      // For complex types, convert to JSON string
      valueStr = valueElement.toString();
    }

    // Update the valuation using setCastedValue which handles type conversion
    try {
      // Special case: the LLM may use the string "null" as a type signature/value
      // for reference variables that should be null. In this case, set the
      // reference value to 0 (JPF's null reference) directly.
      if ("null".equals(valueStr)) {
        val.setCastedValue(var, 0);
        System.out.println("Updated variable " + varName + " = 0 (null object reference from type signature \"null\")");
        return;
      }

      // Check if the value is a type signature (object reference case)
      // Type signatures have format: L...; (e.g., Ljava/lang/Object;)
      boolean isTypeSignature = valueStr != null &&
          valueStr.length() >= 3 &&
          valueStr.startsWith("L") &&
          valueStr.endsWith(";");

      if (isTypeSignature) {
        createObjectFromTypeSignature(valueStr, var, varName, val);
      }
    } catch (Exception e) {
      System.err.println("Failed to set value for variable " + varName + ": " + e.getMessage());
      e.printStackTrace();
    }
  }

  /**
   * Create a new object from type signature and update the valuation.
   * Type signatures have format: L...; (e.g., Ljava/lang/Object;)
   */
  private void createObjectFromTypeSignature(String typeSignature, Variable<?> var, String varName, Valuation val) {
    String className = typeSignature.substring(1, typeSignature.length() - 1);

    // Get the current VM instance from the ongoing concolic execution
    // VM.getVM() returns the VM instance that is currently executing in JPF
    VM vm = VM.getVM();
    if (vm == null) {
      System.err.println("Warning: VM.getVM() returned null, not in JPF execution context");
      return;
    }

    // Check if the variable already has a value in the valuation
    Object currentValue = val.getValue(var);
    boolean needNewObject = true;

    if (currentValue != null) {
      // If current value is an object reference (int), check its type
      if (currentValue instanceof Integer) {
        int currentObjRef = (Integer) currentValue;
        Heap heap = vm.getHeap();
        ElementInfo currentObj = heap.get(currentObjRef);

        if (currentObj != null && !currentObj.isNull()) {
          // Get the class name of the current object
          String currentClassName = currentObj.getClassInfo().getName();

          // If types match, keep the existing object reference
          if (currentClassName.equals(className)) {
            needNewObject = false;
            System.out.println("Variable " + varName + " already has matching type " + className
                + ", keeping existing object reference " + currentObjRef);
          }
        }
      }
    }

    // Only create a new object if types don't match or no value exists
    if (needNewObject) {
      ClassLoaderInfo sysCl = ClassLoaderInfo.getCurrentSystemClassLoader();
      if (sysCl == null) {
        System.err.println("Warning: System class loader unavailable, cannot resolve class " + className);
        return;
      }

      ClassInfo ci;
      try {
        ci = sysCl.getResolvedClassInfo(className);
      } catch (ClassInfoException cie) {
        System.err.println("Warning: Failed to resolve class for type signature " + typeSignature + ": " + cie.getMessage());
        return;
      }

      Heap heap = vm.getHeap();
      ThreadInfo ti = vm.getCurrentThread();

      if (heap == null || ti == null) {
        System.err.println("Warning: Missing heap or current thread, cannot allocate object for " + className);
        return;
      }

      try {
        if (!ci.isInitialized()) {
          ci.initializeClassAtomic(ti);
        }
      } catch (RuntimeException initEx) {
        System.err.println("Warning: Failed to initialize class " + className + " before allocation: " + initEx.getMessage());
        initEx.printStackTrace();
        return;
      }

      ElementInfo newObjectEi = heap.newObject(ci, ti);

      // Get the object reference (an int value representing the object ID in the
      // heap)
      int objRef = newObjectEi.getObjectRef();

      // Store the object reference in the valuation
      // Use setCastedValue to properly handle type conversion
      val.setCastedValue(var, objRef);
      System.out.println(
          "Updated variable " + varName + " = " + objRef + " (object reference for type " + typeSignature + ")");
      
      // Symbolize the new object to track and collect constraints on its fields
      symbolizeNewObject(newObjectEi, varName, ti);
    }
  }

  /**
   * Symbolize a newly created object to track and collect constraints on its fields.
   * This method retrieves the current symbolic execution context and processes the object
   * polymorphically to handle different runtime types.
   */
  private void symbolizeNewObject(ElementInfo newObjectEi, String varName, ThreadInfo ti) {
    try {
      // Get the current concolic method explorer from the thread
      ConcolicMethodExplorer currentAnalysisExplorer = 
          ConcolicMethodExplorer.getCurrentAnalysis(ti);
      
      if (currentAnalysisExplorer == null) {
        System.err.println("Warning: Cannot symbolize new object - no active ConcolicMethodExplorer");
        return;
      }
      
      // Get the symbolic objects context
      gov.nasa.jpf.jdart.objects.SymbolicObjectsContext symContext = currentAnalysisExplorer.getSymbolicObjectsContext();
      
      if (symContext == null) {
        System.err.println("Warning: Cannot symbolize new object - no SymbolicObjectsContext");
        return;
      }
      
      // Process the object polymorphically to handle different types and fields
      symContext.processPolymorphicObject(newObjectEi, varName);
      System.out.println("Successfully symbolized new object: " + varName + " of type " + 
                        newObjectEi.getClassInfo().getName());
      
    } catch (Exception e) {
      System.err.println("Error symbolizing new object " + varName + ": " + e.getMessage());
      e.printStackTrace();
    }
  }
  
  private static boolean containsHighLevel(Expression<?> e) {
    if (e == null) {
      return false;
    }
    if (e.isHighLevel()) {
      return true;
    }
    Expression<?>[] children = e.getChildren();
    if (children == null || children.length == 0) {
      return false;
    }
    for (Expression<?> c : children) {
      if (containsHighLevel(c)) {
        return true;
      }
    }
    return false;
  }

}
