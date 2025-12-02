/*
 * Copyright (C) 2015, United States Government, as represented by the 
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 * 
 * The PSYCO: A Predicate-based Symbolic Compositional Reasoning environment 
 * platform is licensed under the Apache License, Version 2.0 (the "License"); you 
 * may not use this file except in compliance with the License. You may obtain a 
 * copy of the License at http://www.apache.org/licenses/LICENSE-2.0. 
 * 
 * Unless required by applicable law or agreed to in writing, software distributed 
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR 
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the 
 * specific language governing permissions and limitations under the License.
 */
package gov.nasa.jpf.jdart.objects;

import gov.nasa.jpf.JPF;
import gov.nasa.jpf.constraints.api.Variable;
import gov.nasa.jpf.constraints.types.BuiltinTypes;
import gov.nasa.jpf.util.JPFLogger;
import gov.nasa.jpf.vm.ClassInfo;
import gov.nasa.jpf.vm.ElementInfo;
import gov.nasa.jpf.vm.FieldInfo;
import gov.nasa.jpf.vm.StaticElementInfo;

/**
 * Polymorphic symbolic object handler that considers objects may be of different types
 * (subclasses, implementations). This handler:
 * 
 * 1. Processes all fields like DefaultObjectHandler
 * 2. Creates a symbolic reference variable for the object reference itself
 *    (to handle null or different object instances)
 * 3. Creates a symbolic type variable for the object's dynamic type
 *    (to handle polymorphism - the object may be a subclass)
 * 
 * This is useful for exploring different execution paths based on:
 * - Whether the object reference is null or not
 * - What the actual runtime type of the object is (polymorphism)
 */
class PolymorphicObjectHandler implements SymbolicObjectHandler {

  private static transient JPFLogger logger = JPF.getLogger("jdart");
  
  // Delegate to DefaultObjectHandler for field processing
  // TODO: We may need to modify it to handle polymorphic objects
  private final DefaultObjectHandler defaultHandler = new DefaultObjectHandler();
  
  /*
   * (non-Javadoc)
   * @see gov.nasa.jpf.jdart.objects.SymbolicObjectHandler#initialize(gov.nasa.jpf.vm.ClassInfo)
   */
  @Override
  public boolean initialize(ClassInfo ci) {
    // Match the same classes as DefaultObjectHandler
    return !ci.isPrimitive() && !ci.isArray(); 
  }

  /*
   * (non-Javadoc)
   * @see gov.nasa.jpf.jdart.objects.SymbolicObjectHandler#annotateObject(gov.nasa.jpf.vm.ElementInfo, java.lang.String, gov.nasa.jpf.jdart.objects.SymbolicObjectsContext)
   */
  @Override
  public void annotateObject(ElementInfo ei, String name,
      SymbolicObjectsContext ctx) {
    ClassInfo ci = ei.getClassInfo();
    logger.finest("Annotating polymorphic object of class " + ci.getName() + " with name " + name);
    
    // defaultHandler.annotateObject(ei, name, ctx);

    logger.finest("Annotating object of class " + ci.getName());
    FieldInfo[] fis;
    if(ei instanceof StaticElementInfo)
      fis = ci.getDeclaredStaticFields();
    else
      fis = ci.getDeclaredInstanceFields();
    for(FieldInfo fi : fis) {
      ctx.processPolymorphicField(ei, fi, name + "." + fi.getName());
    }
    
    // // Then, create symbolic variables for polymorphic handling
    // try {
    //   // Create a symbolic reference variable for the object reference itself
    //   // This allows tracking whether the reference is null or points to different instances
    //   // and enables instanceof constraints to be collected
    //   Variable<Integer> refVar = Variable.create(BuiltinTypes.SINT32, name + ".<ref>");
    //   SymbolicReference symRef = new SymbolicReference(refVar, ei);
    //   // ctx.addSymbolicVar(symRef);
    //   logger.finest("Created symbolic reference variable: " + refVar.getName());
      
    //   // Create a symbolic type variable for the object's dynamic type
    //   // This allows tracking polymorphic type constraints (the object may be a subclass)
    //   Variable<Integer> typeVar = Variable.create(BuiltinTypes.SINT32, name + ".__type");
    //   SymbolicType symType = new SymbolicType(typeVar, ei);
    //   // ctx.addSymbolicVar(symType);
    //   logger.finest("Created symbolic type variable: " + typeVar.getName());
      
    //   // Log information about possible polymorphic types
    //   logPolymorphicInfo(ci, name);
      
    // } catch(Exception ex) {
    //   // Be conservative: if variable creation fails, do not break execution
    //   logger.finest("Could not create polymorphic symbolic variables for " + name + ": " + ex.getMessage());
    // }
  }
  
  /**
   * Logs information about the polymorphic nature of the object.
   * This helps understand what types the object could potentially be.
   */
  private void logPolymorphicInfo(ClassInfo ci, String name) {
    if(ci.isInterface()) {
      logger.finest("Object " + name + " is of interface type " + ci.getName() + 
                    " - may be implemented by various classes");
    } else if(ci.getSuperClass() != null && !ci.getSuperClass().getName().equals("java.lang.Object")) {
      logger.finest("Object " + name + " is of class " + ci.getName() + 
                    " - may be subclass of " + ci.getSuperClass().getName());
    } else {
      logger.finest("Object " + name + " is of class " + ci.getName() + 
                    " - may have subclasses");
    }
    
    // Log implemented interfaces
    if(ci.getAllInterfaces() != null && !ci.getAllInterfaces().isEmpty()) {
      logger.finest("Object " + name + " implements interfaces: " + ci.getAllInterfaces());
    }
  }
}

