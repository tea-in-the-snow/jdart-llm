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
package gov.nasa.jpf.jdart.bytecode;

import gov.nasa.jpf.constraints.api.Expression;
import gov.nasa.jpf.constraints.expressions.Negation;
import gov.nasa.jpf.constraints.expressions.ReferenceComparisonExpression;
import gov.nasa.jpf.jdart.ConcolicInstructionFactory;
import gov.nasa.jpf.jdart.ConcolicMethodExplorer;
import gov.nasa.jpf.vm.Instruction;
import gov.nasa.jpf.vm.StackFrame;
import gov.nasa.jpf.vm.ThreadInfo;

/**
 * Symbolic execution of IF_ACMPEQ instruction.
 * Compares two object references for equality and branches if they are equal.
 */
public class IF_ACMPEQ extends gov.nasa.jpf.jvm.bytecode.IF_ACMPEQ {

  public IF_ACMPEQ(int targetPosition) {
    super(targetPosition);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Instruction execute(ThreadInfo ti) {
    ConcolicMethodExplorer analysis = ConcolicMethodExplorer.getCurrentAnalysis(ti);
    StackFrame sf = ti.getModifiableTopFrame();

    // No concolic analysis or no symbolic attributes => fall back to default behavior
    Expression<?> rsym = (Expression<?>) sf.getOperandAttr(0);
    Expression<?> lsym = (Expression<?>) sf.getOperandAttr(1);
    
    if (analysis == null || (rsym == null && lsym == null)) {
      return super.execute(ti);
    }

    // Get concrete values
    int rightRef = sf.peek(0);
    int leftRef = sf.peek(1);
    sf.pop(2);

    // Concrete evaluation: branches if references are equal
    boolean sat = (leftRef == rightRef);

    // Create symbolic constraints
    Expression<Boolean>[] constraints = null;
    if (analysis.needsDecisions()) {
      constraints = new Expression[2];
      
      // If both are symbolic, create full symbolic comparison
      if (lsym != null && rsym != null) {
        Expression<Boolean> eqExpr = new ReferenceComparisonExpression(lsym, rsym, true);
        constraints[0] = eqExpr;  // Branch taken: left == right
        constraints[1] = new Negation(eqExpr);  // Branch not taken: left != right
      } else {
        // One is concrete, one is symbolic
        // For reference comparison, we need to handle this carefully
        Expression<?> concreteExpr = createReferenceConstant(
            (lsym != null) ? rightRef : leftRef);
        Expression<Boolean> eqExpr = new ReferenceComparisonExpression(
            lsym != null ? lsym : concreteExpr, 
            rsym != null ? rsym : concreteExpr, 
            true);
        
        constraints[0] = eqExpr;
        constraints[1] = new Negation(eqExpr);
      }
    }

    int branchIdx = sat ? 0 : 1;
    analysis.decision(ti, this, branchIdx, constraints);

    Instruction next = sat ? getTarget() : getNext(ti);

    if (ConcolicInstructionFactory.DEBUG) {
      ConcolicInstructionFactory.logger.finest(
          "Execute IF_ACMPEQ: ref1=" + leftRef + " [" + lsym + "] == ref2=" + 
          rightRef + " [" + rsym + "], result [" + sat + "]");
    }

    conditionValue = (next == this.target);
    return next;
  }

  /**
   * Create a constant expression for a reference value.
   */
  private Expression<?> createReferenceConstant(int ref) {
    return gov.nasa.jpf.constraints.expressions.Constant.create(
        gov.nasa.jpf.constraints.types.BuiltinTypes.SINT32, ref);
  }
}
