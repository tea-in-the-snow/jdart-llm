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
import gov.nasa.jpf.jdart.constraints.PostCondition;
import gov.nasa.jpf.vm.ElementInfo;
import gov.nasa.jpf.vm.StackFrame;

/**
 * Symbolic variable for an object reference. This variable can be used
 * in instanceof expressions to track polymorphic type constraints.
 */
public class SymbolicReference extends SymbolicVariable<Integer> {
  private final ElementInfo elementInfo;
  
  public SymbolicReference(Variable<Integer> variable, ElementInfo elementInfo) {
    super(variable);
    this.elementInfo = elementInfo;
  }

  @Override
  public void readInitial(Valuation initVal, StackFrame sf) {
    // The reference value is the object reference ID
    int refValue = elementInfo.getObjectRef();
    initVal.setCastedValue(variable, refValue);
    // Store the variable as an attribute on the ElementInfo so it can be
    // retrieved when the object is used (e.g., in instanceof checks)
    elementInfo.defreeze();
    elementInfo.setObjectAttr(variable);
  }

  @Override
  public void apply(Valuation val, StackFrame sf) {
    // For object references, we typically don't modify the reference itself
    // during symbolic execution, but we ensure the variable is available
    // The variable is stored as an attribute so it can be used in instanceof checks
    elementInfo.defreeze();
    elementInfo.setObjectAttr(variable);
  }

  @Override
  @SuppressWarnings("unchecked")
  public void addToPC(PostCondition pc) {
    // Get the current expression from the element info, or use the variable
    Expression<Integer> expr = elementInfo.getObjectAttr(Expression.class);
    if(expr == null) {
      expr = variable;
    }
    pc.addCondition(variable, expr);
  }
  
  public ElementInfo getElementInfo() {
    return elementInfo;
  }
}

