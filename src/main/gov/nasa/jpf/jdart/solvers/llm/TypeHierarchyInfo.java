package gov.nasa.jpf.jdart.solvers.llm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import gov.nasa.jpf.vm.ClassInfo;

/**
 * Collects and represents type hierarchy information for reference types.
 * This information can be used by the LLM solver to understand inheritance
 * relationships and polymorphism when solving constraints.
 */
public class TypeHierarchyInfo {
  
  private final String className;
  private final String typeSignature;
  private final boolean isInterface;
  private final boolean isAbstract;
  private final boolean isArray;
  private final String superClassName;
  private final List<String> directInterfaces;
  private final List<String> allSuperClasses;
  private final List<String> allInterfaces;
  
  /**
   * Private constructor - use factory method extractFrom() instead.
   */
  private TypeHierarchyInfo(String className, String typeSignature, boolean isInterface,
                           boolean isAbstract, boolean isArray, String superClassName,
                           List<String> directInterfaces, List<String> allSuperClasses,
                           List<String> allInterfaces) {
    this.className = className;
    this.typeSignature = typeSignature;
    this.isInterface = isInterface;
    this.isAbstract = isAbstract;
    this.isArray = isArray;
    this.superClassName = superClassName;
    this.directInterfaces = directInterfaces;
    this.allSuperClasses = allSuperClasses;
    this.allInterfaces = allInterfaces;
  }
  
  /**
   * Extract type hierarchy information from a ClassInfo object.
   * 
   * @param ci the ClassInfo to extract information from
   * @return TypeHierarchyInfo containing the extracted information, or null if ci is null
   */
  public static TypeHierarchyInfo extractFrom(ClassInfo ci) {
    if (ci == null) {
      return null;
    }
    
    String className = ci.getName();
    String typeSignature = ci.getType();
    boolean isInterface = ci.isInterface();
    boolean isAbstract = ci.isAbstract();
    boolean isArray = ci.isArray();
    
    // Get super class
    String superClassName = null;
    ClassInfo superClass = ci.getSuperClass();
    if (superClass != null) {
      superClassName = superClass.getName();
    }
    
    // Get direct interfaces - use getSuperClassName() to check if interfaceNames method exists,
    // or use a more direct approach based on available API
    List<String> directInterfaces = new ArrayList<>();
    // Since interfaceNames is a protected field, we need to collect it differently
    // We'll derive it from the difference between getAllInterfaces of this class and superclass
    Set<ClassInfo> allIfcsSet = ci.getAllInterfaces();
    Set<ClassInfo> superIfcsSet = new HashSet<>();
    if (superClass != null) {
      superIfcsSet = superClass.getAllInterfaces();
    }
    
    // Direct interfaces are those in allIfcs but not in superIfcs
    for (ClassInfo ifc : allIfcsSet) {
      if (!superIfcsSet.contains(ifc)) {
        directInterfaces.add(ifc.getName());
      }
    }
    
    // Get all super classes (walking up the hierarchy)
    List<String> allSuperClasses = new ArrayList<>();
    ClassInfo currentSuper = superClass;
    while (currentSuper != null && !currentSuper.isObjectClassInfo()) {
      allSuperClasses.add(currentSuper.getName());
      currentSuper = currentSuper.getSuperClass();
    }
    
    // Get all interfaces (including inherited ones)
    List<String> allInterfaces = new ArrayList<>();
    if (allIfcsSet != null) {
      for (ClassInfo ifc : allIfcsSet) {
        allInterfaces.add(ifc.getName());
      }
    }
    
    return new TypeHierarchyInfo(className, typeSignature, isInterface, isAbstract,
                                isArray, superClassName, directInterfaces,
                                allSuperClasses, allInterfaces);
  }
  
  /**
   * Convert this type hierarchy information to a human-readable string
   * suitable for sending to an LLM.
   */
  public String toDescriptiveString() {
    StringBuilder sb = new StringBuilder();
    
    sb.append("Type: ").append(className);
    sb.append(" (signature: ").append(typeSignature).append(")");
    
    if (isInterface) {
      sb.append(" [interface]");
    } else if (isAbstract) {
      sb.append(" [abstract class]");
    } else if (isArray) {
      sb.append(" [array]");
    }
    
    if (superClassName != null) {
      sb.append("\n  Extends: ").append(superClassName);
    }
    
    if (!directInterfaces.isEmpty()) {
      sb.append("\n  Implements: ");
      sb.append(String.join(", ", directInterfaces));
    }
    
    if (!allSuperClasses.isEmpty()) {
      sb.append("\n  Class hierarchy: ");
      sb.append(className).append(" -> ");
      sb.append(String.join(" -> ", allSuperClasses));
      sb.append(" -> java.lang.Object");
    }
    
    if (!allInterfaces.isEmpty()) {
      sb.append("\n  All interfaces: ");
      sb.append(String.join(", ", allInterfaces));
    }
    
    return sb.toString();
  }
  
  // Getters
  public String getClassName() {
    return className;
  }
  
  public String getTypeSignature() {
    return typeSignature;
  }
  
  public boolean isInterface() {
    return isInterface;
  }
  
  public boolean isAbstract() {
    return isAbstract;
  }
  
  public boolean isArray() {
    return isArray;
  }
  
  public String getSuperClassName() {
    return superClassName;
  }
  
  public List<String> getDirectInterfaces() {
    return directInterfaces;
  }
  
  public List<String> getAllSuperClasses() {
    return allSuperClasses;
  }
  
  public List<String> getAllInterfaces() {
    return allInterfaces;
  }
}
