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
package gov.nasa.jpf.jdart;

import gov.nasa.jpf.constraints.api.Expression;
import gov.nasa.jpf.constraints.api.Valuation;
import gov.nasa.jpf.constraints.api.Variable;
import gov.nasa.jpf.constraints.expressions.Constant;
import gov.nasa.jpf.constraints.types.BuiltinTypes;
import gov.nasa.jpf.jdart.constraints.PostCondition;
import gov.nasa.jpf.vm.ClassInfo;
import gov.nasa.jpf.vm.ElementInfo;
import gov.nasa.jpf.vm.StackFrame;

/**
 * Symbolic variable for an object's dynamic type. This variable represents
 * the actual runtime type of the object, which may be a subclass of the
 * declared type. This enables tracking polymorphic type constraints.
 */
public class SymbolicType extends SymbolicVariable<Integer> {
  private final ElementInfo elementInfo;
  
  public SymbolicType(Variable<Integer> variable, ElementInfo elementInfo) {
    super(variable);
    this.elementInfo = elementInfo;
  }

  @Override
  public void readInitial(Valuation initVal, StackFrame sf) {
    // The type value is the ClassInfo ID
    ClassInfo ci = elementInfo.getClassInfo();
    int typeValue = ci.getId();
    initVal.setCastedValue(variable, typeValue);
    elementInfo.defreeze();
    // Store the type variable as an attribute for potential future use
    elementInfo.addObjectAttr(variable);
  }

  @Override
  public void apply(Valuation val, StackFrame sf) {
    // The type variable represents the dynamic type, which may change
    // in different execution paths (polymorphism)
    // The variable is stored as an attribute for potential future use
    elementInfo.defreeze();
    elementInfo.addObjectAttr(variable);
  }

  @Override
  @SuppressWarnings("unchecked")
  public void addToPC(PostCondition pc) {
    // Get the current type expression, or use a constant for the current type
    ClassInfo ci = elementInfo.getClassInfo();
    Expression<Integer> expr = elementInfo.getObjectAttr(Expression.class);
    if(expr == null) {
      // Use the current class ID as the initial value
      expr = Constant.create(BuiltinTypes.SINT32, ci.getId());
    }
    pc.addCondition(variable, expr);
  }
  
  public ElementInfo getElementInfo() {
    return elementInfo;
  }
}

