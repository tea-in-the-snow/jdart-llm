package gov.nasa.jpf.jdart.bytecode;

import gov.nasa.jpf.vm.ClassInfo;
import gov.nasa.jpf.vm.Instruction;
import gov.nasa.jpf.vm.MethodInfo;
import gov.nasa.jpf.vm.ThreadInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Overridden INVOKEINTERFACE instruction to collect constraints for interface
 * method calls.
 * When invoking an interface method, we need to ensure that the object's dynamic
 * type contains a concrete implementation of the method.
 */
public class INVOKEINTERFACE extends gov.nasa.jpf.jvm.bytecode.INVOKEINTERFACE {

  private final AbstractPolymorphicInvocation helper = new AbstractPolymorphicInvocation() {
    @Override
    protected String getInstructionName() {
      return "INVOKEINTERFACE";
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
      return INVOKEINTERFACE.this.getPosition();
    }

    @Override
    protected int getArgSize() {
      return INVOKEINTERFACE.this.getArgSize();
    }

    @Override
    protected MethodInfo getInvokedMethod(ThreadInfo ti, int objRef) {
      return INVOKEINTERFACE.this.getInvokedMethod(ti, objRef);
    }

    @Override
    protected List<ClassInfo> collectPossibleImplementingTypes(
        ThreadInfo ti, ClassInfo declaredType, ClassInfo actualType,
        String methodName, String methodSignature) {
      return INVOKEINTERFACE.this.collectPossibleImplementingTypes(
          ti, declaredType, actualType, methodName, methodSignature);
    }
  };

  public INVOKEINTERFACE() {
    super();
  }

  public INVOKEINTERFACE(String clsDescriptor, String methodName, String signature) {
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
   * Collect all types that may implement the specified interface method.
   * This includes all classes that implement the interface and have a concrete 
   * implementation of the method.
   */
  private List<ClassInfo> collectPossibleImplementingTypes(
      ThreadInfo ti, ClassInfo declaredType, ClassInfo actualType,
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
            helper.scanClassLoaderWithFilter(classLoader, 
                ci -> implementsInterface(ci, declaredType) && hasConcreteMethod(ci, methodName, methodSignature),
                discoveredClasses);
          }
        }
      } catch (Exception e) {
        System.err.println(
            "Failed to scan loaded classes: " + e.getMessage());
      }
    }

    // Strategy 3: Check if the actual type implements the interface
    if (actualType != null && implementsInterface(actualType, declaredType) 
        && hasConcreteMethod(actualType, methodName, methodSignature)) {
      discoveredClasses.add(actualType);
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
   */
  private boolean hasConcreteMethod(ClassInfo classInfo, String methodName, String methodSignature) {
    if (classInfo == null || classInfo.isAbstract() || classInfo.isInterface()) {
      return false;
    }
    MethodInfo mi = classInfo.getMethod(methodName, methodSignature, true);
    return mi != null && !mi.isAbstract();
  }

  /**
   * Check if a class implements a specific interface.
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
}
