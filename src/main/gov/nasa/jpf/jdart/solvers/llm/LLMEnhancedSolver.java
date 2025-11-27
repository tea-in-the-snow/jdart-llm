package gov.nasa.jpf.jdart.solvers.llm;

import gov.nasa.jpf.constraints.api.ConstraintSolver;
import gov.nasa.jpf.constraints.api.Expression;
import gov.nasa.jpf.constraints.api.Valuation;
import gov.nasa.jpf.constraints.api.ConstraintSolver.Result;
import gov.nasa.jpf.constraints.api.SolverContext;

public class LLMEnhancedSolver extends ConstraintSolver {

  ConstraintSolver baseSolver;

  public LLMEnhancedSolver(ConstraintSolver baseSolver) {
    this.baseSolver = baseSolver;
  }

  public LLMEnhancedSolver() {
    this.baseSolver = null;
  }

  @Override
  public Result solve(Expression<Boolean> f, Valuation result) {
    return baseSolver != null ? baseSolver.solve(f, result) : Result.DONT_KNOW;
  }
  
  @Override
  public SolverContext createContext() {
    return new LLMEnhancedSolverContext(baseSolver != null ? baseSolver.createContext() : null);
  }
  
}
