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
import gov.nasa.jpf.constraints.expressions.IsNullExpression;
import gov.nasa.jpf.constraints.expressions.Negation;
import gov.nasa.jpf.jdart.ConcolicInstructionFactory;
import gov.nasa.jpf.jdart.ConcolicMethodExplorer;
import gov.nasa.jpf.vm.Instruction;
import gov.nasa.jpf.vm.StackFrame;
import gov.nasa.jpf.vm.ThreadInfo;

// we should factor out some of the code and put it in a parent class for all "if statements"
// TODO: to review: approximation

public class IFNONNULL extends gov.nasa.jpf.jvm.bytecode.IFNONNULL {

  public IFNONNULL(int targetPc) {
    super(targetPc);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Instruction execute(ThreadInfo ti) {
    ConcolicMethodExplorer analysis = ConcolicMethodExplorer.getCurrentAnalysis(ti);
    StackFrame sf = ti.getModifiableTopFrame();

    // No concolic analysis or no symbolic attribute => fall back to default behavior
    Expression<?> symb = (Expression<?>) sf.getOperandAttr();
    if (analysis == null || symb == null) {
      return super.execute(ti);
    }

    System.out.println("**********************************************************");
    System.out.println("Execute IFNONNULL: symb=" + symb);
    System.out.println("**********************************************************");

    int ref = sf.peek();
    sf.pop();

    boolean sat = (ref != 0); // IFNONNULL branches if reference is non-null

    Expression<Boolean>[] constraints = null;
    if (analysis.needsDecisions()) {
      constraints = new Expression[2];
      Expression<Boolean> isNullExpr = new IsNullExpression(symb);
      // Branch 0: taken when condition (non-null) is true => !isNull
      constraints[0] = new Negation(isNullExpr);
      // Branch 1: not taken => isNull
      constraints[1] = isNullExpr;
    }

    int branchIdx = sat ? 0 : 1;
    analysis.decision(ti, this, branchIdx, constraints);

    Instruction next = sat ? getTarget() : getNext(ti);

    if (ConcolicInstructionFactory.DEBUG) {
      ConcolicInstructionFactory.logger.finest(
          "Execute IFNONNULL: ref=" + ref + " [" + symb + "], symb. result [" + sat + "]");
    }

    conditionValue = (next == this.target);
    return next;
  }
}
