package gov.nasa.jpf.jdart.bytecode;

import gov.nasa.jpf.constraints.api.Expression;
import gov.nasa.jpf.constraints.expressions.InstanceofExpression;
import gov.nasa.jpf.constraints.expressions.LogicalOperator;
import gov.nasa.jpf.constraints.expressions.Negation;
import gov.nasa.jpf.constraints.expressions.PropositionalCompound;
import gov.nasa.jpf.jdart.ConcolicMethodExplorer;
import gov.nasa.jpf.vm.ClassInfo;
import gov.nasa.jpf.vm.Instruction;
import gov.nasa.jpf.vm.MJIEnv;
import gov.nasa.jpf.vm.MethodInfo;
import gov.nasa.jpf.vm.StackFrame;
import gov.nasa.jpf.vm.ThreadInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract base class for polymorphic method invocation instructions
 * (INVOKEVIRTUAL and INVOKEINTERFACE).
 * 
 * This class provides common functionality for collecting type constraints
 * during symbolic execution of polymorphic method calls.
 */
public abstract class AbstractPolymorphicInvocation {

  /**
   * Cache for possible types at each call site.
   * Key: unique identifier for the call site (method + position + target method)
   * Value: list of possible implementing types
   * 
   * This cache ensures consistent type ordering across multiple executions of the same
   * decision point, which is essential for correct branch index calculation during
   * JDart's path replay.
   */
  private static final Map<String, List<ClassInfo>> possibleTypesCache = new ConcurrentHashMap<>();

  /**
   * Clear the possible types cache.
   * Should be called at the start of a new analysis to ensure fresh state.
   */
  public static void clearCache() {
    possibleTypesCache.clear();
  }

  /**
   * Get the instruction name for logging purposes.
   * @return instruction name (e.g., "INVOKEVIRTUAL" or "INVOKEINTERFACE")
   */
  protected abstract String getInstructionName();

  /**
   * Get the method name from the bytecode instruction.
   */
  protected abstract String getMethodName();

  /**
   * Get the method signature from the bytecode instruction.
   */
  protected abstract String getMethodSignature();

  /**
   * Get the class name from the bytecode instruction.
   */
  protected abstract String getClassName();

  /**
   * Get the position of this instruction in the bytecode.
   */
  protected abstract int getPosition();

  /**
   * Get the argument size for this invocation.
   */
  protected abstract int getArgSize();

  /**
   * Get the invoked method info.
   */
  protected abstract MethodInfo getInvokedMethod(ThreadInfo ti, int objRef);

  /**
   * Collect all possible implementing types for the method.
   * Subclasses implement this to handle virtual methods vs interface methods differently.
   * 
   * @param ti              ThreadInfo
   * @param declaredType    The declared type (class or interface)
   * @param actualType      The runtime type observed during the current execution
   * @param methodName      Method name
   * @param methodSignature Method signature
   * @return List of types that may implement the method
   */
  protected abstract List<ClassInfo> collectPossibleImplementingTypes(
      ThreadInfo ti, ClassInfo declaredType, ClassInfo actualType,
      String methodName, String methodSignature);

  /**
   * Execute the polymorphic invocation with symbolic execution support.
   * This method should be called BEFORE super.execute() to collect symbolic constraints.
   * @param ti ThreadInfo
   * @param instruction The instruction instance (this) to pass to analysis.decision()
   */
  protected void executePolymorphicInvocation(ThreadInfo ti, Instruction instruction) {
    ConcolicMethodExplorer analysis = ConcolicMethodExplorer.getCurrentAnalysis(ti);

    // If no concolic analysis is available, do nothing
    if (analysis == null) {
      return;
    }

    // Get the stack frame and the target object reference for the call
    int argSize = getArgSize();
    int objRef = ti.getCalleeThis(argSize);
    StackFrame sf = ti.getModifiableTopFrame();

    // Check if the object reference is null
    if (objRef == MJIEnv.NULL) {
      // A null reference will cause a NullPointerException, skip symbolic execution
      return;
    }

    // Get the symbolic expression corresponding to the object reference
    // Note: The object reference is at position argSize - 1 in the stack (because
    // it's below the arguments)
    Object objAttr = sf.getOperandAttr(argSize - 1);

    // If the object reference is symbolic, handle type constraints
    if (objAttr instanceof Expression) {
      Expression<?> symbolicObjRef = (Expression<?>) objAttr;

      // Get information about the method to be invoked
      MethodInfo callee = getInvokedMethod(ti, objRef);

      if (callee != null) {
        String methodName = callee.getName();
        String methodSignature = callee.getSignature();

        // Get the static declared type
        ClassInfo declaredType = callee.getClassInfo();
        ClassInfo actualType = ti.getClassInfo(objRef);

        // Generate cache key for this call site
        // IMPORTANT: Use the static type from bytecode instead of the resolved type
        // because the resolved type changes based on actual object type during re-execution
        String cacheKey = getCacheKey(sf.getMethodInfo(), getPosition(), 
            getClassName() + "." + getMethodName() + getMethodSignature());

        // Check if this is the first visit to this decision point
        boolean isFirstVisit = analysis.needsDecisions();
        
        // Get or compute possible types
        List<ClassInfo> possibleTypes;
        if (isFirstVisit) {
          // First visit: collect and cache possible types
          possibleTypes = collectPossibleImplementingTypes(
              ti, declaredType, actualType, methodName, methodSignature);

          if (actualType != null && !possibleTypes.contains(actualType)) {
            possibleTypes.add(actualType);
            possibleTypes.sort(this::compareBySpecificity);
          }
          
          // Cache the types for subsequent executions
          possibleTypesCache.put(cacheKey, new ArrayList<>(possibleTypes));
        } else {
          // Re-execution: use cached types to ensure consistent ordering
          possibleTypes = possibleTypesCache.get(cacheKey);
          if (possibleTypes == null) {
            // Fallback: recompute if cache miss (shouldn't happen normally)
            System.err.println("WARNING: Cache miss for " + cacheKey + ", recomputing types");
            possibleTypes = collectPossibleImplementingTypes(
                ti, declaredType, actualType, methodName, methodSignature);
            if (actualType != null && !possibleTypes.contains(actualType)) {
              possibleTypes.add(actualType);
              possibleTypes.sort(this::compareBySpecificity);
            }
          }
        }

        if (!possibleTypes.isEmpty()) {
          // Build constraints only on first visit to this decision point
          Expression<Boolean>[] constraints = null;
          
          if (isFirstVisit) {
            System.out.println("\n\n++++++++++++++++++++++++++++++++++++++++++++++");
            System.out.println(getInstructionName() + ": collecting constraints for method call to " + getMethodName());

            // Debug output of possible types
            System.out.println();
            System.out.println("Possible types implementing " + callee.getFullName() + ":");
            for (ClassInfo ci : possibleTypes) {
              System.out.println("  Possible type: " + ci.getName());
            }
            System.out.println();

            constraints = buildExclusiveTypeConstraints(symbolicObjRef, possibleTypes);

            System.out.println("Generated instanceof constraints for " + getInstructionName() + ":");
            for (int i = 0; i < constraints.length; i++) {
              System.out.println("  Branch " + i + ": " + constraints[i]);
            }
            System.out.println();

            System.out.println("++++++++++++++++++++++++++++++++++++++++++++++\n\n");
          }

          // Determine branch index based on actual runtime type
          int branchIdx = findTypeIndex(possibleTypes, actualType);

          if (branchIdx < 0) {
            if (actualType != null) {
              System.err.println(
                  "Could not determine branch index for actual type " + actualType.getName() +
                      " when invoking " + callee.getFullName() + 
                      " (possibleTypes: " + possibleTypes + ")");
            }
            branchIdx = 0;
          }

          // Always call decision() to update constraint tree position
          // On first visit: constraints are recorded
          // On re-execution: constraints is null, but branchIdx is validated against expectedPath
          analysis.decision(ti, instruction, branchIdx, constraints);

          if (isFirstVisit && actualType != null) {
            System.out.println(
                getInstructionName() + " instanceof constraints: " + possibleTypes.size() +
                    " possible types for method " + callee.getFullName() +
                    ", selected branch: " + branchIdx + " (" + actualType.getName() + ")");
          }
        } else {
          System.err.println(
              "No candidate types collected for " + callee.getFullName() +
                  "; falling back to concrete execution without branching");
        }
      }
    }
  }

  /**
   * Generate a unique key for caching possible types at this call site.
   */
  protected String getCacheKey(MethodInfo callerMethod, int position, String targetMethod) {
    return callerMethod.getFullName() + "@" + position + "->" + targetMethod;
  }

  /**
   * Build exclusive type constraints for each possible type.
   * Each constraint ensures that the object is of a specific type AND not any of the previous types.
   */
  @SuppressWarnings("unchecked")
  protected Expression<Boolean>[] buildExclusiveTypeConstraints(
      Expression<?> symbolicObjRef, List<ClassInfo> possibleTypes) {

    Expression<Boolean>[] constraints = (Expression<Boolean>[]) new Expression[possibleTypes.size()];
    List<Expression<Boolean>> baseChecks = new ArrayList<>(possibleTypes.size());

    for (ClassInfo typeOption : possibleTypes) {
      baseChecks.add(new InstanceofExpression(symbolicObjRef, typeOption.getName()));
    }

    for (int i = 0; i < possibleTypes.size(); i++) {
      Expression<Boolean> constraint = baseChecks.get(i);
      for (int j = 0; j < i; j++) {
        constraint = new PropositionalCompound(
            constraint,
            LogicalOperator.AND,
            new Negation(baseChecks.get(j)));
      }
      constraints[i] = constraint;
    }

    return constraints;
  }

  /**
   * Compare two types by specificity (more specific types first).
   * Uses inheritance depth and then name as tie-breaker.
   */
  protected int compareBySpecificity(ClassInfo left, ClassInfo right) {
    int depthDiff = Integer.compare(getInheritanceDepth(right), getInheritanceDepth(left));
    if (depthDiff != 0) {
      return depthDiff;
    }
    return left.getName().compareTo(right.getName());
  }

  /**
   * Get the inheritance depth of a type (distance from Object).
   */
  protected int getInheritanceDepth(ClassInfo type) {
    int depth = 0;
    ClassInfo current = type;
    while (current != null) {
      depth++;
      current = current.getSuperClass();
    }
    return depth;
  }

  /**
   * Find the index of the specified type in the type list.
   * Uses class name comparison to handle cases where ClassInfo instances
   * may differ across executions.
   */
  protected int findTypeIndex(List<ClassInfo> types, ClassInfo targetType) {
    if (targetType == null) {
      return -1;
    }
    String targetName = targetType.getName();
    for (int i = 0; i < types.size(); i++) {
      if (types.get(i).getName().equals(targetName)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Scan a class loader for classes and add discovered ones to the set.
   * The filter determines which classes to include.
   */
  protected void scanClassLoaderWithFilter(
      gov.nasa.jpf.vm.ClassLoaderInfo classLoader,
      ClassInfoFilter filter,
      java.util.Set<ClassInfo> discoveredClasses) {

    if (classLoader == null) {
      return;
    }

    // Iterate through all loaded classes in this class loader
    for (ClassInfo ci : classLoader) {
      if (ci != null && filter.accept(ci)) {
        discoveredClasses.add(ci);
      }
    }
  }

  /**
   * Filter interface for class selection.
   */
  protected interface ClassInfoFilter {
    boolean accept(ClassInfo classInfo);
  }
}
