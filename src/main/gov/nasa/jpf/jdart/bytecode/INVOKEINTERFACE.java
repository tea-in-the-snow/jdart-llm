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
 * Overridden INVOKEINTERFACE instruction to collect constraints for interface
 * method calls.
 * When invoking an interface method, we need to ensure that the object's dynamic
 * type contains
 * a concrete implementation of the method.
 */
public class INVOKEINTERFACE extends gov.nasa.jpf.jvm.bytecode.INVOKEINTERFACE {

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

  public INVOKEINTERFACE() {
    super();
  }

  public INVOKEINTERFACE(String clsDescriptor, String methodName, String signature) {
    super(clsDescriptor, methodName, signature);
  }

  /**
   * Generate a unique key for caching possible types at this call site.
   */
  private String getCacheKey(MethodInfo callerMethod, int position, String targetMethod) {
    return callerMethod.getFullName() + "@" + position + "->" + targetMethod;
  }

  /**
   * Clear the possible types cache.
   * Should be called at the start of a new analysis to ensure fresh state.
   */
  public static void clearCache() {
    possibleTypesCache.clear();
  }

  @Override
  public Instruction execute(ThreadInfo ti) {
    ConcolicMethodExplorer analysis = ConcolicMethodExplorer.getCurrentAnalysis(ti);

    // If no concolic analysis is available, fall back to default behavior
    if (analysis == null) {
      return super.execute(ti);
    }

    // Get the stack frame and the target object reference for the call
    int argSize = getArgSize();
    int objRef = ti.getCalleeThis(argSize);
    StackFrame sf = ti.getModifiableTopFrame();

    // Check if the object reference is null
    if (objRef == MJIEnv.NULL) {
      // A null reference will cause a NullPointerException, let the parent class
      // handle it
      return super.execute(ti);
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

        // Get the static declared type (the interface where the method is declared)
        ClassInfo declaredType = callee.getClassInfo();
        ClassInfo actualType = ti.getClassInfo(objRef);

        // Generate cache key for this call site
        // IMPORTANT: Use the static type from bytecode (cname) instead of the resolved type
        // because the resolved type changes based on actual object type during re-execution
        String cacheKey = getCacheKey(sf.getMethodInfo(), getPosition(), 
            cname + "." + mname + signature);

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
            System.out.println("INVOKEINTERFACE: collecting constraints for method call to " + mname);

            // Debug output of possible types
            System.out.println();
            System.out.println("Possible types implementing " + callee.getFullName() + ":");
            for (ClassInfo ci : possibleTypes) {
              System.out.println("  Possible type: " + ci.getName());
            }
            System.out.println();

            constraints = buildExclusiveTypeConstraints(symbolicObjRef, possibleTypes);

            System.out.println("Generated instanceof constraints for INVOKEINTERFACE:");
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
          analysis.decision(ti, this, branchIdx, constraints);

          if (isFirstVisit && actualType != null) {
            System.out.println(
                "INVOKEINTERFACE instanceof constraints: " + possibleTypes.size() +
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

    // Execute the actual method call
    return super.execute(ti);
  }

  /**
   * Collect all types that may implement the specified interface method.
   * This includes all classes that implement the interface and have a concrete 
   * implementation of the method.
   * 
   * Uses ASM to scan classpath for implementing classes without loading them.
   * This is more efficient and comprehensive than runtime reflection.
   * 
   * @param ti              ThreadInfo
   * @param declaredType    The interface where the method is declared
   * @param actualType      The runtime type observed during the current execution
   * @param methodName      Method name
   * @param methodSignature Method signature
   * @return List of types that may implement the method
   */
  private List<ClassInfo> collectPossibleImplementingTypes(
      ThreadInfo ti, ClassInfo declaredType,
      ClassInfo actualType,
      String methodName, String methodSignature) {

    List<ClassInfo> types = new ArrayList<>();
    java.util.Set<ClassInfo> discoveredClasses = new java.util.HashSet<>();

    // Strategy 1: Use ASM to scan classpath for all implementing classes
    try {
      java.util.Set<String> implementingClassNames = SubclassScanner.findSubclasses(ti, declaredType);

      // Try to load each discovered implementing class as ClassInfo
      gov.nasa.jpf.vm.ClassLoaderInfo classLoader = declaredType.getClassLoaderInfo();

      for (String internalName : implementingClassNames) {
        try {
          // Convert internal name (com/example/Foo) to binary name (com.example.Foo)
          String className = internalName.replace('/', '.');

          // Try to get or resolve the ClassInfo
          ClassInfo ci = classLoader.tryGetResolvedClassInfo(className);

          if (ci != null && !ci.isAbstract() && !ci.isInterface()) {
            // Verify this class has the method
            MethodInfo mi = ci.getMethod(methodName, methodSignature, true);
            if (mi != null && !mi.isAbstract()) {
              discoveredClasses.add(ci);
            }
          }
        } catch (Exception e) {
          // Skip classes that can't be loaded
          System.out.println(
              "Could not load class " + internalName + ": " + e.getMessage());
        }
      }
    } catch (Exception e) {
      System.err.println(
          "Failed to scan classpath with ASM: " + e.getMessage());
    }

    // Strategy 2: Fallback - scan already loaded classes in all class loaders
    if (discoveredClasses.isEmpty()) {
      try {
        gov.nasa.jpf.vm.VM vm = ti.getVM();
        gov.nasa.jpf.vm.ClassLoaderList classLoaderList = vm.getKernelState().getClassLoaderList();

        for (gov.nasa.jpf.vm.ClassLoaderInfo classLoader : classLoaderList) {
          if (classLoader != null) {
            scanClassLoaderForImplementingClasses(classLoader, declaredType, methodName, methodSignature, discoveredClasses);
          }
        }
      } catch (Exception e) {
        System.err.println(
            "Failed to scan loaded classes: " + e.getMessage());
      }
    }

    // Strategy 3: Check if the actual type implements the interface
    if (actualType != null && !actualType.isAbstract() && !actualType.isInterface()) {
      if (implementsInterface(actualType, declaredType)) {
        MethodInfo mi = actualType.getMethod(methodName, methodSignature, true);
        if (mi != null && !mi.isAbstract()) {
          discoveredClasses.add(actualType);
        }
      }
    }

    // Convert set to list and sort for deterministic ordering
    types.addAll(discoveredClasses);
    types.sort(this::compareBySpecificity);

    // If no concrete types were found, add the declared type as a placeholder
    if (types.isEmpty()) {
      types.add(declaredType);

      System.err.println(
          "No concrete implementations found for " + declaredType.getName() +
              "." + methodName + ", using declared type as placeholder");
    } else {
      System.out.println(
          "Found " + types.size() + " possible implementations for " +
              declaredType.getName() + "." + methodName + ": " + types);
    }

    return types;
  }

  @SuppressWarnings("unchecked")
  private Expression<Boolean>[] buildExclusiveTypeConstraints(
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

  private int compareBySpecificity(ClassInfo left, ClassInfo right) {
    int depthDiff = Integer.compare(getInheritanceDepth(right), getInheritanceDepth(left));
    if (depthDiff != 0) {
      return depthDiff;
    }
    return left.getName().compareTo(right.getName());
  }

  private int getInheritanceDepth(ClassInfo type) {
    int depth = 0;
    ClassInfo current = type;
    while (current != null) {
      depth++;
      current = current.getSuperClass();
    }
    return depth;
  }

  /**
   * Scan a class loader for all loaded classes that implement the interface
   * and have a concrete implementation of the specified method.
   * 
   * @param classLoader       The class loader to scan
   * @param interfaceClass    The interface to look for implementations of
   * @param methodName        Method name
   * @param methodSignature   Method signature
   * @param discoveredClasses Set to add discovered classes to
   */
  private void scanClassLoaderForImplementingClasses(
      gov.nasa.jpf.vm.ClassLoaderInfo classLoader,
      ClassInfo interfaceClass,
      String methodName,
      String methodSignature,
      java.util.Set<ClassInfo> discoveredClasses) {

    if (classLoader == null) {
      return;
    }

    // Iterate through all loaded classes in this class loader
    for (ClassInfo ci : classLoader) {
      if (ci != null && implementsInterface(ci, interfaceClass)) {
        // Check if this class has a concrete implementation of the method
        MethodInfo mi = ci.getMethod(methodName, methodSignature, true);
        if (mi != null && !mi.isAbstract() && !ci.isAbstract() && !ci.isInterface()) {
          discoveredClasses.add(ci);
        }
      }
    }
  }

  /**
   * Check if a class implements a specific interface.
   * 
   * @param candidate The candidate class
   * @param interfaceClass The interface
   * @return true if candidate implements interfaceClass
   */
  private boolean implementsInterface(ClassInfo candidate, ClassInfo interfaceClass) {
    if (candidate == null || interfaceClass == null) {
      return false;
    }

    // Check direct equality
    if (candidate.equals(interfaceClass)) {
      return true;
    }

    // Check if candidate implements the interface
    return candidate.getAllInterfaces().contains(interfaceClass);
  }

  /**
   * Find the index of the specified type in the type list.
   * Uses class name comparison to handle cases where ClassInfo instances
   * may differ across executions.
   * 
   * @param types      List of types
   * @param targetType Target type
   * @return Index of the type in the list, or -1 if not found
   */
  private int findTypeIndex(List<ClassInfo> types, ClassInfo targetType) {
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
}
