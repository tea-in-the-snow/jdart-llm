package gov.nasa.jpf.jdart.bytecode;

import gov.nasa.jpf.vm.ClassInfo;
import gov.nasa.jpf.vm.ClassLoaderInfo;
import gov.nasa.jpf.vm.Instruction;
import gov.nasa.jpf.vm.MethodInfo;
import gov.nasa.jpf.vm.ThreadInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Overridden INVOKEVIRTUAL instruction to collect constraints for virtual
 * method calls.
 * When invoking a virtual method, we need to ensure that the object's dynamic
 * type contains a concrete implementation of the method.
 */
public class INVOKEVIRTUAL extends gov.nasa.jpf.jvm.bytecode.INVOKEVIRTUAL {

  private final AbstractPolymorphicInvocation helper = new AbstractPolymorphicInvocation() {
    @Override
    protected String getInstructionName() {
      return "INVOKEVIRTUAL";
    }

    @Override
    protected String getMethodName() {
      return mname;
    }

    @Override
    protected String getMethodSignature() {
      return signature;
    }

    @Override
    protected String getClassName() {
      return cname;
    }

    @Override
    protected int getPosition() {
      return INVOKEVIRTUAL.this.getPosition();
    }

    @Override
    protected int getArgSize() {
      return INVOKEVIRTUAL.this.getArgSize();
    }

    @Override
    protected MethodInfo getInvokedMethod(ThreadInfo ti, int objRef) {
      return INVOKEVIRTUAL.this.getInvokedMethod(ti, objRef);
    }

    @Override
    protected List<ClassInfo> collectPossibleImplementingTypes(
        ThreadInfo ti, ClassInfo declaredType, ClassInfo actualType,
        String methodName, String methodSignature) {
      return INVOKEVIRTUAL.this.collectPossibleImplementingTypes(
          ti, declaredType, actualType, methodName, methodSignature);
    }

    @Override
    protected boolean hasConcreteMethod(
        ClassInfo classInfo, String methodName, String methodSignature) {
      return INVOKEVIRTUAL.this.hasConcreteMethod(classInfo, methodName, methodSignature);
    }
  };

  public INVOKEVIRTUAL() {
    super();
  }

  public INVOKEVIRTUAL(String clsDescriptor, String methodName, String signature) {
    super(clsDescriptor, methodName, signature);
  }

  /**
   * Clear the possible types cache.
   * Should be called at the start of a new analysis to ensure fresh state.
   */
  public static void clearCache() {
    AbstractPolymorphicInvocation.clearCache();
  }

  @Override
  public Instruction execute(ThreadInfo ti) {
    // Handle symbolic execution before calling super.execute()
    helper.executePolymorphicInvocation(ti, this);
    // Now execute the actual method invocation
    return super.execute(ti);
  }

  /**
   * Collect all types that may implement the specified method.
   * This includes the class declaring the method and all its subclasses found via
   * ASM scanning.
   */
  private List<ClassInfo> collectPossibleImplementingTypes(
      ThreadInfo ti, ClassInfo declaredType, ClassInfo actualType,
      String methodName, String methodSignature) {

    List<ClassInfo> types = new ArrayList<>();
    java.util.Set<ClassInfo> discoveredClasses = new java.util.HashSet<>();

    // Strategy 1: Use ASM to scan classpath for all subclasses
    try {
      System.out.println(
          "Scanning classpath for subclasses of " + declaredType.getName() + "\n");
      java.util.Set<String> subclassNames = SubclassScanner.findSubclasses(ti, actualType);

      // Try to load each discovered subclass as ClassInfo
      // gov.nasa.jpf.vm.ClassLoaderInfo classLoader = declaredType.getClassLoaderInfo();
      ClassLoaderInfo classLoader = actualType.getClassLoaderInfo();

      for (String internalName : subclassNames) {
        try {
          // Convert internal name (com/example/Foo) to binary name (com.example.Foo)
          String className = internalName.replace('/', '.');

          // Try to get or resolve the ClassInfo
          ClassInfo ci = classLoader.tryGetResolvedClassInfo(className);

          if (ci != null && !ci.isAbstract() && !ci.isInterface()) {
            // Check if the class can access the method (either has it directly or inherits it)
            MethodInfo mi = ci.getMethod(methodName, methodSignature, true);
            if (mi != null && !mi.isAbstract()) {
              System.out.println("\n" +
                  "Discovered subclass: " + ci.getName() + 
                  (hasConcreteMethod(ci, methodName, methodSignature) ? " (has concrete method)" : " (inherits method)"));
              System.out.println("  Method: " + methodName + methodSignature + "\n");
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
            helper.scanClassLoaderWithFilter(classLoader, 
                ci -> {
                  if (!isSubclassOf(ci, declaredType)) {
                    return false;
                  }
                  // Check if the class can access the method (either has it directly or inherits it)
                  MethodInfo mi = ci.getMethod(methodName, methodSignature, true);
                  return mi != null && !mi.isAbstract();
                },
                discoveredClasses);
          }
        }
      } catch (Exception e) {
        System.err.println(
            "Failed to scan loaded classes: " + e.getMessage());
      }
    }

    // Strategy 3: Check the declared type itself if it's concrete
    if (!declaredType.isAbstract() && !declaredType.isInterface()) {
      MethodInfo mi = declaredType.getMethod(methodName, methodSignature, true);
      if (mi != null && !mi.isAbstract()) {
        discoveredClasses.add(declaredType);
      }
    }

    if (actualType != null) {
      MethodInfo mi = actualType.getMethod(methodName, methodSignature, true);
      if (mi != null && !mi.isAbstract()) {
      discoveredClasses.add(actualType);
      }
    }

    // Convert set to list and sort for deterministic ordering
    types.addAll(discoveredClasses);
    types.sort(helper::compareBySpecificity);

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

  /**
   * Check if a class has a concrete implementation of the method.
   * Only checks methods defined directly in this class, not inherited from parents.
   */
  protected boolean hasConcreteMethod(ClassInfo classInfo, String methodName, String methodSignature) {
    if (classInfo == null || classInfo.isAbstract() || classInfo.isInterface()) {
      return false;
    }
    // Use false to only check methods defined in this class, not inherited ones
    MethodInfo mi = classInfo.getMethod(methodName, methodSignature, false);
    return mi != null && !mi.isAbstract();
  }

  /**
   * Check if a class is a subclass of (or the same as) another class.
   */
  private boolean isSubclassOf(ClassInfo candidate, ClassInfo baseClass) {
    if (candidate == null || baseClass == null) {
      return false;
    }

    // Check direct equality
    if (candidate.equals(baseClass)) {
      return true;
    }

    // Check if candidate is a subclass
    ClassInfo current = candidate.getSuperClass();
    while (current != null) {
      if (current.equals(baseClass)) {
        return true;
      }
      current = current.getSuperClass();
    }

    // Check if baseClass is an interface and candidate implements it
    if (baseClass.isInterface()) {
      return candidate.getAllInterfaces().contains(baseClass);
    }

    return false;
  }
}
