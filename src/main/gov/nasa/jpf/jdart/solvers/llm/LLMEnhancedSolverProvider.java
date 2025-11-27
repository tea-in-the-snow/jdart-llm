package gov.nasa.jpf.jdart.solvers.llm;

import java.util.Properties;

import gov.nasa.jpf.constraints.api.ConstraintSolver;
import gov.nasa.jpf.constraints.solvers.ConstraintSolverProvider;

public class LLMEnhancedSolverProvider implements ConstraintSolverProvider {

  @Override
  public String[] getNames() {
    // this method is maybe of no use
    // provide a set of names that can be used with the ConstraintSolverFactory
    return new String[]{"llm", "LLM", "llm-enhanced"};
  }

  @Override
  public ConstraintSolver createSolver(Properties config) {
    // create a minimal LLM enhanced solver. We don't yet wire any backend,
    // so return a solver that answers DONT_KNOW for queries.
    return new LLMEnhancedSolver();
  }
  
}
