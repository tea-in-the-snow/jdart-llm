package gov.nasa.jpf.jdart.solvers.llm;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import gov.nasa.jpf.constraints.api.Expression;
import gov.nasa.jpf.constraints.api.Valuation;
import gov.nasa.jpf.constraints.api.ValuationEntry;
import gov.nasa.jpf.constraints.api.Variable;
import gov.nasa.jpf.constraints.util.ExpressionUtil;
import gov.nasa.jpf.vm.ClassInfo;
import gov.nasa.jpf.vm.ElementInfo;
import gov.nasa.jpf.vm.FieldInfo;
import gov.nasa.jpf.vm.Heap;
import gov.nasa.jpf.vm.MJIEnv;
import gov.nasa.jpf.vm.StackFrame;
import gov.nasa.jpf.vm.ThreadInfo;

/**
 * Collects heap state information by performing reachability analysis
 * from API parameters and local variables. Only collects objects that
 * are reachable from high-level constraint reference variables to minimize LLM context.
 * 
 * The output format includes:
 * - bindings: mapping from constraint reference names to object IDs
 * - objects: heap objects with their fields
 * - modifiable_objects: list of object IDs that can be modified
 * - schemas: class field schemas for LLM understanding
 * - allowed_to_allocate: whether new objects can be created
 */
public class HeapStateCollector {

  private static final int DEFAULT_MAX_DEPTH = 10;
  private static final int DEFAULT_MAX_OBJECTS = 100;

  private final int maxDepth;
  private final int maxObjects;
  private final Set<String> irrelevantFields;
  
  // Pattern to extract reference variable base names like "node(ref)" from expressions
  // Handles both quoted ('node(ref)') and unquoted (node(ref)) formats
  private static final Pattern REF_VAR_PATTERN = Pattern.compile("'?([a-zA-Z_][a-zA-Z0-9_.]*\\(ref\\))'?");

  /**
   * Configuration for heap state collection.
   */
  public static class Config {
    int maxDepth = DEFAULT_MAX_DEPTH;
    int maxObjects = DEFAULT_MAX_OBJECTS;
    Set<String> irrelevantFields = new HashSet<>();

    public Config() {
      // Common fields that usually don't affect path conditions
      irrelevantFields.add("modCount");
      irrelevantFields.add("size");
      irrelevantFields.add("capacity");
      irrelevantFields.add("hash");
      irrelevantFields.add("EMPTY_ELEMENTDATA");
      irrelevantFields.add("threshold");
      irrelevantFields.add("loadFactor");
    }

    public Config maxDepth(int maxDepth) {
      this.maxDepth = maxDepth;
      return this;
    }

    public Config maxObjects(int maxObjects) {
      this.maxObjects = maxObjects;
      return this;
    }

    public Config addIrrelevantField(String fieldName) {
      this.irrelevantFields.add(fieldName);
      return this;
    }
  }

  public HeapStateCollector(Config config) {
    this.maxDepth = config.maxDepth;
    this.maxObjects = config.maxObjects;
    this.irrelevantFields = config.irrelevantFields;
  }

  public static HeapStateCollector createDefault() {
    return new HeapStateCollector(new Config());
  }

  /**
   * Collect heap state from the current execution context with high-level constraints.
   * This version performs constraint-aware heap slicing to only include relevant objects.
   * 
   * @param ti The current thread
   * @param val The current valuation (may contain reference values)
   * @param hlExpressions High-level constraint expressions for heap slicing
   * @return JSON object containing heap state with bindings, objects, schemas, etc.
   */
  public JsonObject collectHeapState(ThreadInfo ti, Valuation val, List<Expression<Boolean>> hlExpressions) {
    if (ti == null) {
      return null;
    }

    Heap heap = ti.getHeap();
    if (heap == null) {
      return null;
    }

    // Step 1: Extract reference variable names from high-level constraints
    Set<String> refVarNames = extractReferenceVariableNames(hlExpressions);
    
    // Step 2: Extract relevant class names from constraint variable names
    Set<String> relevantClassNames = extractRelevantClassNames(refVarNames, hlExpressions);
    
    // Step 3: Build bindings from reference variable names to object IDs
    Map<String, Integer> bindings = buildBindings(val, refVarNames);
    
    // Step 4: Collect root references based on bindings or relevant classes
    Set<Integer> rootRefs = new HashSet<>();
    boolean hasValidBindings = false;
    
    for (Integer ref : bindings.values()) {
      if (ref != null && ref > 0) {
        rootRefs.add(ref);
        hasValidBindings = true;
      }
    }
    
    // If no valid bindings, find objects of relevant types in the heap
    if (!hasValidBindings && !relevantClassNames.isEmpty()) {
      rootRefs = findObjectsByType(heap, relevantClassNames);
    }
    
    // If still no roots, fall back to valuation references (but this shouldn't include unrelated objects)
    if (rootRefs.isEmpty()) {
      // Only fall back if we have no constraint information at all
      if (refVarNames.isEmpty()) {
        rootRefs = collectRootReferences(ti, val);
      }
    }
    
    // Step 5: Perform reachability analysis (heap slicing) - only if we have roots
    Map<Integer, Integer> refToDepth = new HashMap<>();
    if (!rootRefs.isEmpty()) {
      refToDepth = performReachabilityAnalysis(heap, rootRefs);
    }

    // Step 6: Build schemas for classes in the heap slice (or relevant classes if no objects)
    Set<String> classesForSchema = collectRelevantClasses(heap, refToDepth.keySet());
    if (classesForSchema.isEmpty()) {
      classesForSchema = relevantClassNames;
    }
    JsonObject schemas = buildSchemas(heap, classesForSchema);

    // Step 7: Build objects map (only sliced objects)
    JsonObject objects = buildObjectsMap(heap, refToDepth);

    // Step 8: Determine modifiable objects (objects bound to constraint variables)
    JsonArray modifiableObjects = buildModifiableObjectsList(bindings);

    // Step 9: Build bindings JSON
    JsonObject bindingsJson = new JsonObject();
    for (Map.Entry<String, Integer> entry : bindings.entrySet()) {
      if (entry.getValue() != null && entry.getValue() > 0) {
        bindingsJson.addProperty(entry.getKey(), entry.getValue());
      } else {
        bindingsJson.add(entry.getKey(), null);
      }
    }

    // Step 10: Create heap state JSON
    JsonObject heapState = new JsonObject();
    heapState.add("bindings", bindingsJson);
    heapState.add("objects", objects);
    heapState.add("modifiable_objects", modifiableObjects);
    heapState.addProperty("allowed_to_allocate", true);
    heapState.add("schemas", schemas);

    return heapState;
  }

  /**
   * Backward-compatible method without high-level constraints.
   * Collects all reachable objects from local variables.
   * 
   * @param ti The current thread
   * @param val The current valuation (may contain reference values)
   * @return JSON object containing heap state with aliases and objects
   */
  public JsonObject collectHeapState(ThreadInfo ti, Valuation val) {
    return collectHeapState(ti, val, null);
  }

  /**
   * Extract reference variable names from high-level constraint expressions.
   * Looks for patterns like "node(ref)", "node(ref).next(ref)", etc.
   */
  private Set<String> extractReferenceVariableNames(List<Expression<Boolean>> hlExpressions) {
    Set<String> refVarNames = new HashSet<>();
    
    if (hlExpressions == null || hlExpressions.isEmpty()) {
      return refVarNames;
    }
    
    for (Expression<Boolean> expr : hlExpressions) {
      // Collect free variables from the expression
      Set<Variable<?>> freeVars = ExpressionUtil.freeVariables(expr);
      for (Variable<?> var : freeVars) {
        String name = var.getName();
        // Check if this is a reference variable (ends with "(ref)")
        if (name.endsWith("(ref)")) {
          refVarNames.add(name);
        }
      }
      
      // Also extract from string representation for complex paths
      String exprStr = expr.toString();
      Matcher matcher = REF_VAR_PATTERN.matcher(exprStr);
      while (matcher.find()) {
        refVarNames.add(matcher.group(1));
      }
    }
    
    return refVarNames;
  }

  /**
   * Extract class names that are relevant to the constraints.
   * Infers class names from variable names like "node(ref)" -> "Node"
   */
  private Set<String> extractRelevantClassNames(Set<String> refVarNames, List<Expression<Boolean>> hlExpressions) {
    Set<String> classNames = new HashSet<>();
    
    for (String varName : refVarNames) {
      // Extract base name from "node(ref)" or "node(ref).next(ref)"
      String baseName = varName;
      if (baseName.contains(".")) {
        // Get the last segment before (ref) for field access like "node(ref).next(ref)"
        String[] parts = baseName.split("\\.");
        baseName = parts[parts.length - 1];
      }
      
      // Remove "(ref)" suffix
      if (baseName.endsWith("(ref)")) {
        baseName = baseName.substring(0, baseName.length() - 5);
      }
      
      // Convert to PascalCase class name (e.g., "node" -> "Node")
      if (!baseName.isEmpty()) {
        String className = Character.toUpperCase(baseName.charAt(0)) + 
                          (baseName.length() > 1 ? baseName.substring(1) : "");
        classNames.add(className);
      }
    }
    
    return classNames;
  }

  /**
   * Find objects in the heap that match the relevant class names.
   */
  private Set<Integer> findObjectsByType(Heap heap, Set<String> classNames) {
    Set<Integer> refs = new HashSet<>();
    
    for (ElementInfo ei : heap.liveObjects()) {
      ClassInfo ci = ei.getClassInfo();
      if (ci != null) {
        String simpleName = ci.getSimpleName();
        String fullName = ci.getName();
        
        // Check if this object's class matches any relevant class
        if (classNames.contains(simpleName) || classNames.contains(fullName)) {
          refs.add(ei.getObjectRef());
        }
      }
    }
    
    return refs;
  }

  /**
   * Build bindings map from reference variable names to object IDs in valuation.
   */
  private Map<String, Integer> buildBindings(Valuation val, Set<String> refVarNames) {
    Map<String, Integer> bindings = new HashMap<>();
    
    if (val == null) {
      // All reference variables unbound
      for (String name : refVarNames) {
        bindings.put(name, null);
      }
      return bindings;
    }
    
    for (String refVarName : refVarNames) {
      Integer refValue = null;
      
      // Try to find the reference value in valuation
      for (ValuationEntry<?> entry : val.entries()) {
        String varName = entry.getVariable().getName();
        if (varName.equals(refVarName)) {
          Object value = entry.getValue();
          if (value instanceof Integer) {
            int ref = (Integer) value;
            if (ref > 0) {
              refValue = ref;
            }
          }
          break;
        }
      }
      
      bindings.put(refVarName, refValue);
    }
    
    return bindings;
  }

  /**
   * Collect relevant class names from the heap slice for schema generation.
   */
  private Set<String> collectRelevantClasses(Heap heap, Set<Integer> objectRefs) {
    Set<String> classes = new HashSet<>();
    
    for (int ref : objectRefs) {
      ElementInfo ei = heap.get(ref);
      if (ei != null) {
        ClassInfo ci = ei.getClassInfo();
        if (ci != null && !ci.isArray() && !isJavaLangClass(ci)) {
          classes.add(ci.getName());
        }
      }
    }
    
    return classes;
  }

  /**
   * Check if a class is a Java standard library class that doesn't need schema.
   */
  private boolean isJavaLangClass(ClassInfo ci) {
    String name = ci.getName();
    return name.startsWith("java.lang.") || 
           name.startsWith("java.util.") ||
           name.startsWith("["); // array types
  }

  /**
   * Build schemas for the relevant classes.
   */
  private JsonObject buildSchemas(Heap heap, Set<String> classNames) {
    JsonObject schemas = new JsonObject();
    
    for (String className : classNames) {
      // Find an instance to get ClassInfo - try both simple name and full name
      ClassInfo ci = null;
      for (ElementInfo ei : heap.liveObjects()) {
        ClassInfo objCi = ei.getClassInfo();
        if (objCi.getName().equals(className) || objCi.getSimpleName().equals(className)) {
          ci = objCi;
          break;
        }
      }
      
      if (ci != null) {
        JsonObject schema = buildClassSchema(ci);
        // Use simple name for readability
        schemas.add(ci.getSimpleName(), schema);
      }
    }
    
    return schemas;
  }

  /**
   * Build schema for a single class showing field names and types.
   */
  private JsonObject buildClassSchema(ClassInfo ci) {
    JsonObject schema = new JsonObject();
    JsonObject fields = new JsonObject();
    
    for (FieldInfo fi : ci.getDeclaredInstanceFields()) {
      if (irrelevantFields.contains(fi.getName())) {
        continue;
      }
      
      String fieldType = getFieldTypeName(fi);
      fields.addProperty(fi.getName(), fieldType);
    }
    
    schema.add("fields", fields);
    return schema;
  }

  /**
   * Get human-readable type name for a field.
   */
  private String getFieldTypeName(FieldInfo fi) {
    if (fi.isReference()) {
      String typeName = fi.getType();
      // Simplify class names
      int lastDot = typeName.lastIndexOf('.');
      if (lastDot >= 0) {
        return typeName.substring(lastDot + 1);
      }
      return typeName;
    } else if (fi.isBooleanField()) {
      return "boolean";
    } else if (fi.isByteField()) {
      return "byte";
    } else if (fi.isCharField()) {
      return "char";
    } else if (fi.isShortField()) {
      return "short";
    } else if (fi.isIntField()) {
      return "int";
    } else if (fi.isLongField()) {
      return "long";
    } else if (fi.isFloatField()) {
      return "float";
    } else if (fi.isDoubleField()) {
      return "double";
    }
    return fi.getType();
  }

  /**
   * Build list of modifiable object IDs (objects bound to constraint variables).
   */
  private JsonArray buildModifiableObjectsList(Map<String, Integer> bindings) {
    JsonArray modifiable = new JsonArray();
    Set<Integer> added = new HashSet<>();
    
    for (Integer ref : bindings.values()) {
      if (ref != null && ref > 0 && !added.contains(ref)) {
        modifiable.add(new JsonPrimitive(String.valueOf(ref)));
        added.add(ref);
      }
    }
    
    return modifiable;
  }

  /**
   * Collect root references from stack frame and valuation.
   */
  private Set<Integer> collectRootReferences(ThreadInfo ti, Valuation val) {
    Set<Integer> roots = new HashSet<>();

    // Collect from current stack frame (local variables)
    StackFrame frame = ti.getTopFrame();
    if (frame != null) {
      // Get method parameters and local variables
      int numSlots = frame.getTopPos() + 1;
      for (int i = 0; i < numSlots; i++) {
        if (frame.isReferenceSlot(i)) {
          int ref = frame.getSlot(i);
          if (ref != MJIEnv.NULL) {
            roots.add(ref);
          }
        }
      }
    }

    // Collect from valuation (symbolic variables that may hold references)
    if (val != null) {
      for (ValuationEntry<?> entry : val.entries()) {
        Object value = entry.getValue();
        if (value instanceof Integer) {
          int ref = (Integer) value;
          // Check if this looks like a valid reference (positive, non-null)
          if (ref > 0 && ti.getHeap().get(ref) != null) {
            roots.add(ref);
          }
        }
      }
    }

    return roots;
  }

  /**
   * Perform BFS reachability analysis from root references.
   * Returns a map from object reference to depth.
   */
  private Map<Integer, Integer> performReachabilityAnalysis(Heap heap, Set<Integer> roots) {
    Map<Integer, Integer> refToDepth = new HashMap<>();
    Queue<Integer> worklist = new ArrayDeque<>();

    // Initialize with roots at depth 0
    for (int ref : roots) {
      worklist.add(ref);
      refToDepth.put(ref, 0);
    }

    // BFS traversal
    while (!worklist.isEmpty() && refToDepth.size() < maxObjects) {
      int ref = worklist.poll();
      int depth = refToDepth.get(ref);

      // Stop if we've reached max depth
      if (depth >= maxDepth) {
        continue;
      }

      ElementInfo ei = heap.get(ref);
      if (ei == null) {
        continue;
      }

      // Traverse reference fields
      ClassInfo ci = ei.getClassInfo();
      if (ci == null) {
        continue;
      }

      // Handle arrays separately
      if (ci.isArray() && ci.getComponentClassInfo() != null && ci.getComponentClassInfo().isReferenceClassInfo()) {
        int length = ei.arrayLength();
        for (int i = 0; i < length && refToDepth.size() < maxObjects; i++) {
          int childRef = ei.getReferenceElement(i);
          if (childRef != MJIEnv.NULL && !refToDepth.containsKey(childRef)) {
            refToDepth.put(childRef, depth + 1);
            worklist.add(childRef);
          }
        }
      } else {
        // Handle regular objects
        for (FieldInfo fi : ci.getDeclaredInstanceFields()) {
          // Skip irrelevant fields
          if (irrelevantFields.contains(fi.getName())) {
            continue;
          }

          if (fi.isReference()) {
            int childRef = ei.getReferenceField(fi);
            if (childRef != MJIEnv.NULL && !refToDepth.containsKey(childRef)) {
              refToDepth.put(childRef, depth + 1);
              worklist.add(childRef);
            }
          }
        }
      }
    }

    return refToDepth;
  }

  /**
   * Build objects map showing object structure (class, fields).
   */
  private JsonObject buildObjectsMap(Heap heap, Map<Integer, Integer> refToDepth) {
    JsonObject objects = new JsonObject();

    for (Map.Entry<Integer, Integer> entry : refToDepth.entrySet()) {
      int ref = entry.getKey();
      ElementInfo ei = heap.get(ref);
      
      if (ei == null) {
        continue;
      }

      JsonObject objDesc = new JsonObject();
      ClassInfo ci = ei.getClassInfo();
      
      // Add class name
      objDesc.addProperty("class", ci.getName());

      // Add fields
      JsonObject fields = new JsonObject();
      
      if (ci.isArray()) {
        // Handle arrays
        objDesc.addProperty("length", ei.arrayLength());
        
        // For reference arrays, show references
        if (ci.getComponentClassInfo() != null && ci.getComponentClassInfo().isReferenceClassInfo()) {
          JsonArray elements = new JsonArray();
          int length = Math.min(ei.arrayLength(), 10); // Limit array size
          for (int i = 0; i < length; i++) {
            int childRef = ei.getReferenceElement(i);
            if (childRef == MJIEnv.NULL) {
              elements.add(new JsonPrimitive("null"));
            } else {
              elements.add(new JsonPrimitive(childRef));
            }
          }
          objDesc.add("elements", elements);
        }
      } else {
        // Handle regular objects - add all instance fields
        for (FieldInfo fi : ci.getDeclaredInstanceFields()) {
          // Skip irrelevant fields
          if (irrelevantFields.contains(fi.getName())) {
            continue;
          }

          String fieldName = fi.getName();
          
          if (fi.isReference()) {
            int childRef = ei.getReferenceField(fi);
            if (childRef == MJIEnv.NULL) {
              fields.addProperty(fieldName, "null");
            } else {
              fields.addProperty(fieldName, childRef);
            }
          } else if (fi.isBooleanField()) {
            fields.addProperty(fieldName, ei.getBooleanField(fi));
          } else if (fi.isByteField()) {
            fields.addProperty(fieldName, ei.getByteField(fi));
          } else if (fi.isCharField()) {
            fields.addProperty(fieldName, (int) ei.getCharField(fi));
          } else if (fi.isShortField()) {
            fields.addProperty(fieldName, ei.getShortField(fi));
          } else if (fi.isIntField()) {
            fields.addProperty(fieldName, ei.getIntField(fi));
          } else if (fi.isLongField()) {
            fields.addProperty(fieldName, ei.getLongField(fi));
          } else if (fi.isFloatField()) {
            fields.addProperty(fieldName, ei.getFloatField(fi));
          } else if (fi.isDoubleField()) {
            fields.addProperty(fieldName, ei.getDoubleField(fi));
          }
        }
      }
      
      objDesc.add("fields", fields);
      objects.add(String.valueOf(ref), objDesc);
    }

    return objects;
  }
}
