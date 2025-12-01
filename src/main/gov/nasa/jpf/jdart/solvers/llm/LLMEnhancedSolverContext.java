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
import gov.nasa.jpf.vm.ClassInfo;
import gov.nasa.jpf.vm.ClassLoaderInfo;
import gov.nasa.jpf.vm.ElementInfo;
import gov.nasa.jpf.vm.Heap;
import gov.nasa.jpf.vm.ThreadInfo;
import gov.nasa.jpf.vm.VM;

import com.google.gson.JsonElement;

public class LLMEnhancedSolverContext extends SolverContext {

  private final SolverContext bashSolverContext;

  /**
   * Stack of high-level constraints per push/pop scope. Each push() creates a
   * new, empty list on the stack. pop(n) removes the top n scopes.
   */
  private final Deque<List<Expression<Boolean>>> highLevelStack = new ArrayDeque<>();
  private final Deque<Map<String, Variable<?>>> hlFreeVarsStack
          = new ArrayDeque<Map<String, Variable<?>>>();

  public LLMEnhancedSolverContext(SolverContext baseSolverContext) {
    this.bashSolverContext = baseSolverContext;
    // initialize base scope for high-level constraints
    this.highLevelStack.push(new ArrayList<>());
    this.hlFreeVarsStack.push(new HashMap<String, Variable<?>>());
  }

  @Override
  public void push() {
    bashSolverContext.push();
    highLevelStack.push(new ArrayList<>());
    Map<String, Variable<?>> fvMap = hlFreeVarsStack.peek();
    hlFreeVarsStack.push(new HashMap<String, Variable<?>>(fvMap));
  }

  @Override
  public void pop(int n) {
    bashSolverContext.pop(n);
    for (int i = 0; i < n && !highLevelStack.isEmpty(); i++) {
      highLevelStack.pop();
    }
    if (highLevelStack.isEmpty()) {
      // ensure there is always at least one scope
      highLevelStack.push(new ArrayList<>());
    }
    hlFreeVarsStack.pop();
  }

  @Override
  public Result solve(Valuation val) {
    // If there are no high-level constraints, delegate to base solver
    boolean hasHighLevel = highLevelStack.stream().anyMatch(list -> !list.isEmpty());
    if (!hasHighLevel) {
      return bashSolverContext.solve(val);
    }

    // First, solve normal (non-high-level) constraints using base solver
    Result baseResult = bashSolverContext.solve(val);
    if (baseResult != Result.SAT) {
      System.out.println("**********************************************************");
      System.out.println("Base constraints are UNSAT, returning UNSAT");
      System.out.println("**********************************************************");
      return baseResult;
    }

    // Base constraints are SAT; now try to solve high-level constraints
    List<Expression<Boolean>> hlExpressions = highLevelStack.stream()
        .flatMap(List::stream)
        .collect(Collectors.toList());

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
   * The service URL can be configured via LLM_SOLVER_URL (default: http://127.0.0.1:8000/solve).
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
        System.err.println("Invalid LLM_SOLVER_TIMEOUT value '" + timeoutEnv + "', using default " + timeoutSeconds + "s");
      }
    }

    return new SolverConfig(solverUrl, timeoutSeconds);
  }

  /**
   * Build JSON payload from high-level expressions and valuation.
   */
  private String buildJsonPayload(List<Expression<Boolean>> hlExpressions, Valuation val) {
    StringBuilder sb = new StringBuilder();
    sb.append('{');
    sb.append("\"constraints\":[");

    // Use only the current (top) high-level scope to avoid mixing constraints
    // from different exploration branches.
    List<Expression<Boolean>> scopeExprs = highLevelStack.peek();
    if (scopeExprs == null) {
      scopeExprs = hlExpressions;
    }

    String constraintsJson = scopeExprs.stream()
        .map(Object::toString)
        .map(LLMEnhancedSolverContext::escapeJson)
        .map(s -> "\"" + s + "\"")
        .collect(Collectors.joining(","));
    sb.append(constraintsJson);
    sb.append("],");
    sb.append("\"valuation\":");
    if (val == null) {
      sb.append("null");
    } else {
      // Convert Valuation to JSON object format: {"varName1": "value1", "varName2": "value2", ...}
      sb.append('{');
      boolean first = true;
      for (ValuationEntry<?> entry : val.entries()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        String varName = entry.getVariable().getName();
        Object value = entry.getValue();
        sb.append('"').append(escapeJson(varName)).append("\":");
        // Convert value to JSON format
        if (value == null) {
          sb.append("null");
        } else if (value instanceof String) {
          sb.append('"').append(escapeJson(value.toString())).append('"');
        } else if (value instanceof Number || value instanceof Boolean) {
          sb.append(value);
        } else {
          // For other types, convert to string and quote it
          sb.append('"').append(escapeJson(value.toString())).append('"');
        }
      }
      sb.append('}');
    }
    sb.append(',');
    // optional hint field for LLM side
    sb.append("\"hint\":\"java-jdart-llm-high-level-constraints\"");
    sb.append('}');

    return sb.toString();
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
    // Simple, dependency-free parsing: look for "result":"SAT"/"UNSAT"/"UNKNOWN" (case-insensitive).
    String normalized = body.toUpperCase();
    if (normalized.contains("\"RESULT\"") && normalized.contains("SAT")) {
      // parse the valuation array from the body
      JsonObject jsonObject = new JsonParser().parse(body).getAsJsonObject();
      JsonArray llmValuationArray = jsonObject.getAsJsonArray("valuation");

      System.out.println("\n===================================================");
      // Update the valuation with the valuation array
      if (val != null && llmValuationArray != null) {
        updateValuationFromLlmResponse(llmValuationArray, val);
      }
      System.out.println("=================================================\n");
      
      return Result.SAT;
    }
    if (normalized.contains("\"RESULT\"") && normalized.contains("UNSAT")) {
      return Result.UNSAT;
    }
    if (normalized.contains("\"RESULT\"") && (normalized.contains("UNKNOWN") || normalized.contains("DONT_KNOW"))) {
      return Result.DONT_KNOW;
    }

    System.err.println("LLM solver response could not be interpreted, body: " + body);
    return Result.DONT_KNOW;
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
    
    // Add free variables from high-level constraints (in case they're not in the valuation yet)
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
            System.out.println("Variable " + varName + " already has matching type " + className + ", keeping existing object reference " + currentObjRef);
          }
        }
      }
    }
    
    // Only create a new object if types don't match or no value exists
    if (needNewObject) {
      // Get ClassInfo from class name using the system class loader
      ClassLoaderInfo sysCl = ClassLoaderInfo.getCurrentSystemClassLoader();
      ClassInfo ci = sysCl.getResolvedClassInfo(className);
      Heap heap = vm.getHeap();
      ThreadInfo ti = vm.getCurrentThread();
      ElementInfo newObjectEi = heap.newObject(ci, ti);
      
      // Get the object reference (an int value representing the object ID in the heap)
      int objRef = newObjectEi.getObjectRef();
      
      // Store the object reference in the valuation
      // Use setCastedValue to properly handle type conversion
      val.setCastedValue(var, objRef);
      System.out.println("Updated variable " + varName + " = " + objRef + " (object reference for type " + typeSignature + ")");
    }
  }

  private static String escapeJson(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
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
      } else {
        normal.add(e);
      }
      Set<Variable<?>> fvs = ExpressionUtil.freeVariables(e);
      for (Variable<?> v : fvs) {
          fvMap.put(v.getName(), v);
      }
      
    }

    // Forward normal constraints to the base solver immediately
    if (!normal.isEmpty()) {
      bashSolverContext.add(normal);
    }

    // Store high-level constraints in the current high-level scope
    if (!high.isEmpty()) {
      List<Expression<Boolean>> current = highLevelStack.peek();
      if (current == null) {
        current = new ArrayList<>();
        highLevelStack.push(current);
      }
      current.addAll(high);
    }
  }

  @Override
  public void dispose() {
    highLevelStack.clear();
    bashSolverContext.dispose();
    hlFreeVarsStack.clear();
  }
}
