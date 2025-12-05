package gov.nasa.jpf.jdart.solvers.llm;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import gov.nasa.jpf.constraints.api.Valuation;
import gov.nasa.jpf.constraints.api.Variable;
import gov.nasa.jpf.jdart.ConcolicMethodExplorer;
import gov.nasa.jpf.vm.ClassInfo;
import gov.nasa.jpf.vm.ClassInfoException;
import gov.nasa.jpf.vm.ClassLoaderInfo;
import gov.nasa.jpf.vm.ElementInfo;
import gov.nasa.jpf.vm.Heap;
import gov.nasa.jpf.vm.ThreadInfo;
import gov.nasa.jpf.vm.VM;

/**
 * Handles updating valuations from LLM solver responses.
 * Responsible for interpreting LLM-provided values and creating necessary
 * objects in the JPF heap.
 */
public class LLMValuationHandler {

  private final Map<String, Variable<?>> variableMap;

  public LLMValuationHandler(Map<String, Variable<?>> variableMap) {
    this.variableMap = variableMap;
  }

  /**
   * Update valuation from LLM response valuation array.
   * 
   * @param llmValuationArray JSON array containing variable assignments from LLM
   * @param val The valuation to update
   */
  public void updateValuationFromLlmResponse(JsonArray llmValuationArray, Valuation val) {
    if (llmValuationArray == null || val == null) {
      return;
    }

    System.out.println("\n===================================================");
    
    // Iterate through each object in the valuation array
    for (int i = 0; i < llmValuationArray.size(); i++) {
      JsonObject valuationObj = llmValuationArray.get(i).getAsJsonObject();

      // Iterate through each key-value pair in the object
      for (Map.Entry<String, JsonElement> entry : valuationObj.entrySet()) {
        String varName = entry.getKey();
        System.out.println("varName: " + varName);
        JsonElement valueElement = entry.getValue();

        // Find the corresponding Variable object
        Variable<?> var = variableMap.get(varName);
        if (var != null) {
          updateVariableValue(var, varName, valueElement, val);
        } else {
          System.out.println("Warning: Variable " + varName + " not found in current valuation, skipping");
        }
      }
    }
    
    System.out.println("=================================================\n");
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
}
