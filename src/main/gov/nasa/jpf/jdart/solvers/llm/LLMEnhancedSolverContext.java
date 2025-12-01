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
import gov.nasa.jpf.constraints.types.BuiltinTypes;
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
      return baseResult;
    }

    // Base constraints are SAT; now try to solve high-level constraints

    List<Expression<Boolean>> hlExpressions = highLevelStack.stream()
        .flatMap(List::stream)
        .collect(Collectors.toList());

    if (hlExpressions.isEmpty()) {
      return baseResult;
    }

    // // Before solving, insert current values for all high-level reference variables into valuation
    // if (val != null) {
    //   VM vm = VM.getVM();
    //   if (vm != null) {
    //     Heap heap = vm.getHeap();
    //     Map<String, Variable<?>> hlFreeVars = hlFreeVarsStack.peek();
        
    //     if (hlFreeVars != null) {
    //       for (Map.Entry<String, Variable<?>> entry : hlFreeVars.entrySet()) {
    //         String varName = entry.getKey();
    //         Variable<?> var = entry.getValue();
            
    //         // Check if this variable is a reference type (Integer type for object references)
    //         if (var.getType().equals(BuiltinTypes.SINT32)) {
    //           // Check if the variable already has a value in the valuation
    //           if (!val.containsValueFor(var)) {
    //             // Try to find the current value from heap objects
    //             // Look for objects that have this variable as an attribute
    //             boolean found = false;
                
    //             // Iterate through all live objects in the heap to find one with matching variable
    //             // This is a simple approach - we look for objects that have the variable stored as an attribute
    //             for (ElementInfo ei : heap.liveObjects()) {
    //               if (ei != null && !ei.isNull()) {
    //                 // Check if this ElementInfo has the variable as an object attribute
    //                 Variable<?> attrVar = ei.getObjectAttr(Variable.class);
    //                 if (attrVar != null && attrVar.equals(var)) {
    //                   // Found the object, use its reference
    //                   int refValue = ei.getObjectRef();
    //                   val.setCastedValue(var, refValue);
    //                   found = true;
    //                   System.out.println("Inserted current value for high-level reference variable " + varName + " = " + refValue);
    //                   break;
    //                 }
    //               }
    //             }
                
    //             // If not found in heap attributes, log a warning
    //             if (!found) {
    //               System.out.println("Warning: Could not find current value for high-level reference variable " + varName);
    //             }
    //           } else {
    //             // Variable already has a value, keep it
    //             Object currentValue = val.getValue(var);
    //             System.out.println("High-level reference variable " + varName + " already has value: " + currentValue);
    //           }
    //         }
    //       }
    //     }
    //   }
    // }

    // Try to call a local LLM-driven service (Python FastAPI) to reason about high-level constraints.
    // The service URL can be configured via the environment variable LLM_SOLVER_URL.
    // The request timeout (in seconds) can be configured via LLM_SOLVER_TIMEOUT, default 10s.
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

    // Build JSON payload. We keep it simple: constraints as strings and a JSON object valuation.
    StringBuilder sb = new StringBuilder();
    sb.append('{');
    sb.append("\"constraints\":[");
    String constraintsJson = hlExpressions.stream()
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

    String payload = sb.toString();

    try {
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
        return Result.DONT_KNOW;
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
        return Result.DONT_KNOW;
      }

      System.out.println("===================================================");
      System.out.println("LLM solver response: " + body);
      System.out.println("===================================================");

      // Simple, dependency-free parsing: look for "result":"SAT"/"UNSAT"/"UNKNOWN" (case-insensitive).
      String normalized = body.toUpperCase();
      if (normalized.contains("\"RESULT\"") && normalized.contains("SAT")) {
        // parse the valuation array from the body
        JsonObject jsonObject = new JsonParser().parse(body).getAsJsonObject();
        JsonArray LLMValuationArray = jsonObject.getAsJsonArray("valuation");
        // if (LLMValuationArray != null) {
        //   System.out.println("===================================================");
        //   System.out.println("LLM solver returned SAT solution with valuation array:");
        //   System.out.println("valuation array: " + LLMValuationArray);
        //   for (int i = 0; i < LLMValuationArray.size(); i++) {
        //     JsonObject valuationObj = LLMValuationArray.get(i).getAsJsonObject();
        //     System.out.println("valuation[" + i + "]: " + valuationObj);
        //   }
        //   System.out.println("===================================================");
        // }


        System.out.println("\n===================================================");
        // Update the valuation with the valuation array
        if (val != null && LLMValuationArray != null) {
          // Build a map from variable name to Variable object from the current valuation

          // get the map from variable name to Variable object
          Map<String, Variable<?>> varNameToVar = new HashMap<String, Variable<?>>();
          
          // Add variables from the current valuation
          // for (ValuationEntry<?> entry : val.entries()) {
          //   Variable<?> v = entry.getVariable();
          //   varNameToVar.put(v.getName(), v);
          // }
          
          // Add free variables from high-level constraints (in case they're not in the valuation yet)
          Map<String, Variable<?>> hlFreeVars = hlFreeVarsStack.peek();
          if (hlFreeVars != null) {
            varNameToVar.putAll(hlFreeVars);
          }
          
          // Iterate through each object in the valuation array
          for (int i = 0; i < LLMValuationArray.size(); i++) {
            JsonObject valuationObj = LLMValuationArray.get(i).getAsJsonObject();
            
            // Iterate through each key-value pair in the object
            for (Map.Entry<String, JsonElement> entry : valuationObj.entrySet()) {
              String varName = entry.getKey();
              System.out.println("varName: " + varName);
              JsonElement valueElement = entry.getValue();
              
              // Find the corresponding Variable object
              Variable<?> var = varNameToVar.get(varName);
              if (var != null) {
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
                  // Check if the value is a type signature (object reference case)
                  // Type signatures have format: L...; (e.g., Ljava/lang/Object;)
                  boolean isTypeSignature = valueStr != null && 
                                            valueStr.length() >= 3 && 
                                            valueStr.startsWith("L") && 
                                            valueStr.endsWith(";");
                  
                  if (isTypeSignature) {
                    // This is an object reference variable - create a new object of the specified type
                    String typeSignature = valueStr;
                    String className = typeSignature.substring(1, typeSignature.length() - 1);

                    // Get the current VM instance from the ongoing concolic execution
                    // VM.getVM() returns the VM instance that is currently executing in JPF
                    VM vm = VM.getVM();
                    if (vm == null) {
                      System.err.println("Warning: VM.getVM() returned null, not in JPF execution context");
                      continue;
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
                      ElementInfo newObject = heap.newObject(ci, ti);
                      
                      // Get the object reference (an int value representing the object ID in the heap)
                      int objRef = newObject.getObjectRef();
                      
                      // Store the object reference in the valuation
                      // Use setCastedValue to properly handle type conversion
                      val.setCastedValue(var, objRef);
                      System.out.println("Updated variable " + varName + " = " + objRef + " (object reference for type " + typeSignature + ")");
                    }
                  }
                } catch (Exception e) {
                  System.err.println("Failed to set value for variable " + varName + ": " + e.getMessage());
                  e.printStackTrace();
                }
              } else {
                System.out.println("Warning: Variable " + varName + " not found in current valuation, skipping");
              }
            }
          }
          System.out.println("=================================================\n");
          
          System.out.println("===================================================");
          System.out.println("Updated valuation: " + val);
          System.out.println("===================================================");
        }
        
        return Result.SAT;
      }
      if (normalized.contains("\"RESULT\"") && normalized.contains("UNSAT")) {
        return Result.UNSAT;
      }
      if (normalized.contains("\"RESULT\"") && (normalized.contains("UNKNOWN") || normalized.contains("DONT_KNOW"))) {
        return Result.DONT_KNOW;
      }

      // Fallback: if the body contains bare SAT/UNSAT keywords.
      // if (normalized.contains("SAT")) {
      //   return Result.SAT;
      // }
      // if (normalized.contains("UNSAT")) {
      //   return Result.UNSAT;
      // }

      System.err.println("LLM solver response could not be interpreted, body: " + body);
      return Result.DONT_KNOW;
    } catch (IOException e) {
      // If the LLM service is unreachable, fall back to base solver's result.
      System.err.println("LLM solver call failed: " + e.getMessage());
      return baseResult;
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
