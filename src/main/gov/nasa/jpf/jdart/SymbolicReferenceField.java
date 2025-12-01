package gov.nasa.jpf.jdart;

import gov.nasa.jpf.constraints.api.Valuation;
import gov.nasa.jpf.constraints.api.Variable;
import gov.nasa.jpf.vm.ElementInfo;
import gov.nasa.jpf.vm.FieldInfo;
import gov.nasa.jpf.vm.MJIEnv;
import gov.nasa.jpf.vm.StackFrame;

public class SymbolicReferenceField extends SymbolicField<Integer> {

  public SymbolicReferenceField(Variable<Integer> variable,
                                ElementInfo elementInfo,
                                FieldInfo fieldInfo) {
    super(variable, elementInfo, fieldInfo);
  }

  @Override
  public void readInitial(Valuation initVal, StackFrame sf) {
    int ref = elementInfo.getReferenceField(fieldInfo);
    initVal.setCastedValue(variable, ref);
    elementInfo.defreeze();
    elementInfo.setFieldAttr(fieldInfo, variable);
  }

  @Override
  public void apply(Valuation val, StackFrame sf) {
    Integer ref = val.getValue(variable);
    int refVal = (ref != null) ? ref : MJIEnv.NULL;
    elementInfo.defreeze();
    elementInfo.setReferenceField(fieldInfo, refVal);
    elementInfo.setFieldAttr(fieldInfo, variable);
  }
}
