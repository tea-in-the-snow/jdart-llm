package gov.nasa.jpf.jdart;

import gov.nasa.jpf.constraints.api.Variable;

public class SymbolicReferenceParam extends SymbolicParam<Integer> {

  public SymbolicReferenceParam(Variable<Integer> variable, int stackOffset) {
    super(variable, stackOffset);
  }
}
