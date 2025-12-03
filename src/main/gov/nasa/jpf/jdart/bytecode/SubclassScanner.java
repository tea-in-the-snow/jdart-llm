package gov.nasa.jpf.jdart.bytecode;

import gov.nasa.jpf.vm.ClassInfo;
import gov.nasa.jpf.vm.ThreadInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Uses ASM to scan classpath for subclasses without loading them.
 * This is more efficient and safer than reflection-based approaches.
 */
public class SubclassScanner {
  
  /**
   * Find all subclasses of a given class by scanning the classpath.
   * 
   * @param ti ThreadInfo to get classpath information
   * @param baseClass The base class to find subclasses of
   * @return Set of class names (in internal format, e.g., "java/util/ArrayList")
   */
  public static Set<String> findSubclasses(ThreadInfo ti, ClassInfo baseClass) {
    Set<String> subclasses = new HashSet<>();
    String baseClassInternalName = baseClass.getName().replace('.', '/');
    boolean isInterface = baseClass.isInterface();
    
    // Get classpath from the class loader
    gov.nasa.jpf.vm.ClassLoaderInfo classLoader = baseClass.getClassLoaderInfo();
    String[] classpathElements = classLoader.getClassPath().getPathNames();
    
    // Scan each classpath element
    for (String cpElement : classpathElements) {
      File file = new File(cpElement);
      
      if (!file.exists()) {
        continue;
      }
      
      try {
        if (file.isDirectory()) {
          scanDirectory(file, "", baseClassInternalName, isInterface, subclasses);
        } else if (file.getName().endsWith(".jar")) {
          scanJar(file, baseClassInternalName, isInterface, subclasses);
        }
      } catch (Exception e) {
        // Log but continue with other classpath elements
        System.err.println("Warning: Failed to scan " + cpElement + ": " + e.getMessage());
      }
    }
    
    return subclasses;
  }
  
  /**
   * Scan a JAR file for subclasses.
   */
  private static void scanJar(File jarFile, String baseClassInternalName, 
                               boolean isInterface, Set<String> subclasses) {
    try (JarFile jar = new JarFile(jarFile)) {
      Enumeration<JarEntry> entries = jar.entries();
      
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        
        if (name.endsWith(".class") && !name.contains("$")) {
          try (InputStream is = jar.getInputStream(entry)) {
            if (isSubclass(is, baseClassInternalName, isInterface)) {
              // Convert internal name to dot-separated format
              String className = name.substring(0, name.length() - 6); // Remove .class
              subclasses.add(className);
            }
          } catch (Exception e) {
            // Skip problematic classes
          }
        }
      }
    } catch (Exception e) {
      // Skip problematic JARs
    }
  }
  
  /**
   * Scan a directory for class files.
   */
  private static void scanDirectory(File dir, String packagePath, 
                                     String baseClassInternalName,
                                     boolean isInterface, Set<String> subclasses) {
    File[] files = dir.listFiles();
    if (files == null) {
      return;
    }
    
    for (File file : files) {
      if (file.isDirectory()) {
        String newPackagePath = packagePath.isEmpty() ? file.getName() : 
                                packagePath + "/" + file.getName();
        scanDirectory(file, newPackagePath, baseClassInternalName, isInterface, subclasses);
      } else if (file.getName().endsWith(".class") && !file.getName().contains("$")) {
        try (InputStream is = new FileInputStream(file)) {
          if (isSubclass(is, baseClassInternalName, isInterface)) {
            String className = packagePath.isEmpty() ? 
                             file.getName().substring(0, file.getName().length() - 6) :
                             packagePath + "/" + file.getName().substring(0, file.getName().length() - 6);
            subclasses.add(className);
          }
        } catch (Exception e) {
          // Skip problematic classes
        }
      }
    }
  }
  
  /**
   * Check if a class (represented by its bytecode) is a subclass of the target.
   * 
   * @param classInputStream InputStream of the .class file
   * @param targetSuperClassInternalName Target superclass in internal format (e.g., "java/util/AbstractList")
   * @param isInterface Whether the target is an interface
   * @return true if this class extends/implements the target
   */
  private static boolean isSubclass(InputStream classInputStream, 
                                     String targetSuperClassInternalName,
                                     boolean isInterface) throws Exception {
    ClassReader reader = new ClassReader(classInputStream);
    SubclassCheckVisitor visitor = new SubclassCheckVisitor(targetSuperClassInternalName, isInterface);
    reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return visitor.isMatch();
  }
  
  /**
   * ASM ClassVisitor that checks if a class extends/implements a target class/interface.
   * This performs a recursive check of the entire inheritance hierarchy.
   */
  private static class SubclassCheckVisitor extends ClassVisitor {
    private final String targetInternalName;
    private final boolean targetIsInterface;
    private final Set<String> checkedClasses = new HashSet<>();
    private boolean isMatch = false;
    private boolean isAbstract = false;
    private boolean isInterface = false;
    private String currentSuperName;
    private String[] currentInterfaces;
    
    public SubclassCheckVisitor(String targetInternalName, boolean targetIsInterface) {
      super(Opcodes.ASM7);
      this.targetInternalName = targetInternalName;
      this.targetIsInterface = targetIsInterface;
    }
    
    @Override
    public void visit(int version, int access, String name, String signature, 
                     String superName, String[] interfaces) {
      // Check if this is an abstract class or interface - we want concrete classes only
      this.isAbstract = (access & Opcodes.ACC_ABSTRACT) != 0;
      this.isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
      
      // Store for potential recursive checking
      this.currentSuperName = superName;
      this.currentInterfaces = interfaces;
      
      // Don't match if this class is abstract or an interface
      if (isAbstract || isInterface) {
        return;
      }
      
      // Check if this class matches the target (either directly or in hierarchy)
      if (targetIsInterface) {
        // Target is an interface, check if this class implements it (directly or indirectly)
        isMatch = implementsInterface(superName, interfaces, targetInternalName);
      } else {
        // Target is a class, check if this extends it (directly or indirectly)
        isMatch = extendsClass(superName, targetInternalName);
      }
    }
    
    /**
     * Recursively check if a class implements a given interface.
     */
    private boolean implementsInterface(String superName, String[] interfaces, String targetInterface) {
      // Prevent infinite recursion
      if (checkedClasses.contains(superName)) {
        return false;
      }
      checkedClasses.add(superName);
      
      // Check direct interfaces
      if (interfaces != null) {
        for (String iface : interfaces) {
          if (targetInterface.equals(iface)) {
            return true;
          }
        }
      }
      
      // Check parent class's interfaces (recursive)
      if (superName != null && !superName.equals("java/lang/Object")) {
        try {
          // Try to read parent class and check its interfaces
          InputStream parentStream = getClassStream(superName);
          if (parentStream != null) {
            try (InputStream is = parentStream) {
              ClassReader reader = new ClassReader(is);
              ParentInterfaceVisitor visitor = new ParentInterfaceVisitor(targetInterface);
              reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
              if (visitor.isMatch()) {
                return true;
              }
            }
          }
        } catch (Exception e) {
          // Parent class not accessible, skip
        }
      }
      
      return false;
    }
    
    /**
     * Recursively check if a class extends a given superclass.
     */
    private boolean extendsClass(String superName, String targetClass) {
      // Prevent infinite recursion
      if (checkedClasses.contains(superName)) {
        return false;
      }
      
      String current = superName;
      while (current != null && !current.equals("java/lang/Object")) {
        checkedClasses.add(current);
        
        if (targetClass.equals(current)) {
          return true;
        }
        
        // Try to read parent class
        try {
          InputStream parentStream = getClassStream(current);
          if (parentStream == null) {
            break;
          }
          
          try (InputStream is = parentStream) {
            ClassReader reader = new ClassReader(is);
            ParentClassVisitor visitor = new ParentClassVisitor();
            reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            current = visitor.getSuperName();
          }
        } catch (Exception e) {
          break;
        }
      }
      
      return false;
    }
    
    /**
     * Helper to get class bytecode stream from classpath.
     */
    private InputStream getClassStream(String internalName) {
      try {
        // Try to load from classpath
        ClassLoader cl = SubclassScanner.class.getClassLoader();
        return cl.getResourceAsStream(internalName + ".class");
      } catch (Exception e) {
        return null;
      }
    }
    
    public boolean isMatch() {
      return isMatch;
    }
  }
  
  /**
   * Simple visitor to extract parent class name.
   */
  private static class ParentClassVisitor extends ClassVisitor {
    private String superName;
    
    public ParentClassVisitor() {
      super(Opcodes.ASM7);
    }
    
    @Override
    public void visit(int version, int access, String name, String signature, 
                     String superName, String[] interfaces) {
      this.superName = superName;
    }
    
    public String getSuperName() {
      return superName;
    }
  }
  
  /**
   * Visitor to check if a class implements a target interface (recursively).
   */
  private static class ParentInterfaceVisitor extends ClassVisitor {
    private final String targetInterface;
    private boolean isMatch = false;
    
    public ParentInterfaceVisitor(String targetInterface) {
      super(Opcodes.ASM7);
      this.targetInterface = targetInterface;
    }
    
    @Override
    public void visit(int version, int access, String name, String signature, 
                     String superName, String[] interfaces) {
      if (interfaces != null) {
        for (String iface : interfaces) {
          if (targetInterface.equals(iface)) {
            isMatch = true;
            return;
          }
        }
      }
    }
    
    public boolean isMatch() {
      return isMatch;
    }
  }
}
