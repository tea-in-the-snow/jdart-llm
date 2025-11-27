package gov.nasa.jpf.jdart.solvers.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

import gov.nasa.jpf.constraints.api.ConstraintSolver.Result;
import gov.nasa.jpf.constraints.api.Expression;
import gov.nasa.jpf.constraints.api.SolverContext;
import gov.nasa.jpf.constraints.api.Valuation;

public class LLMEnhancedSolverContext extends SolverContext {

  private final SolverContext bashSolverContext;

  /**
   * Stack of high-level constraints per push/pop scope. Each push() creates a
   * new, empty list on the stack. pop(n) removes the top n scopes.
   */
  private final Deque<List<Expression<Boolean>>> highLevelStack = new ArrayDeque<>();

  public LLMEnhancedSolverContext(SolverContext baseSolverContext) {
    this.bashSolverContext = baseSolverContext;
    // initialize base scope for high-level constraints
    this.highLevelStack.push(new ArrayList<>());
  }

  @Override
  public void push() {
    bashSolverContext.push();
    highLevelStack.push(new ArrayList<>());
  }

  @Override
  public void pop(int n) {
    bashSolverContext.pop(n);
    for (int i = 0; i < n && !highLevelStack.isEmpty(); i++) {
      highLevelStack.pop();
    }
    if (highLevelStack.isEmpty()) {
      // ensure there is always at least one scope
      highLevelStack.push(new ArrayList<>());
    }
  }

  @Override
  public Result solve(Valuation val) {
    // If there are no high-level constraints, delegate to base solver
    boolean hasHighLevel = highLevelStack.stream().anyMatch(list -> !list.isEmpty());
    if (!hasHighLevel) {
      return bashSolverContext.solve(val);
    }

    // First, solve normal (non-high-level) constraints using base solver
    Result baseResult = bashSolverContext.solve(val);
    if (baseResult != Result.SAT) {
      // If base constraints are UNSAT or UNKNOWN, return that result directly
      // System.out.println("***************************************************");
      // System.out.println("No solution from base solver, result: " + baseResult);
      // System.out.println("***************************************************");
      return baseResult;
    }

    // print the valuation from base solver
    System.out.println("***************************************************");
    System.out.println("Base solver found SAT solution with valuation: " + val);
    System.out.println("***************************************************");

    // Base constraints are SAT; now try to solve high-level constraints

    List<Expression<Boolean>> hlExpressions = highLevelStack.stream()
        .flatMap(List::stream)
        .collect(Collectors.toList());

    if (hlExpressions.isEmpty()) {
      return bashSolverContext.solve(val);
    }

    // TODO: Implement LLM-based reasoning for high-level constraints here.
    // For now, as a temporary behavior, delegate to the base solver.
    // System.out.println("***************************************************");
    // System.out.println("High-level constraints present, but LLM solving not implemented yet.");
    // System.out.println("Number of high-level constraints: " + hlExpressions.size());
    // System.out.println("High-level constraints: " + hlExpressions);
    // System.out.println("***************************************************");
    return Result.SAT;
  }

  private static boolean containsHighLevel(Expression<?> e) {
    if (e == null) {
      return false;
    }
    if (e.isHighLevel()) {
      return true;
    }
    Expression<?>[] children = e.getChildren();
    if (children == null || children.length == 0) {
      return false;
    }
    for (Expression<?> c : children) {
      if (containsHighLevel(c)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void add(List<Expression<Boolean>> expressions) {
    if (expressions == null || expressions.isEmpty()) {
      return;
    }

    List<Expression<Boolean>> normal = new ArrayList<>();
    List<Expression<Boolean>> high = new ArrayList<>();

    for (Expression<Boolean> e : expressions) {
      if (e != null && containsHighLevel(e)) {
        high.add(e);
      } else {
        normal.add(e);
      }
    }

    // Forward normal constraints to the base solver immediately
    if (!normal.isEmpty()) {
      bashSolverContext.add(normal);
    }

    // Store high-level constraints in the current high-level scope
    if (!high.isEmpty()) {
      List<Expression<Boolean>> current = highLevelStack.peek();
      if (current == null) {
        current = new ArrayList<>();
        highLevelStack.push(current);
      }
      current.addAll(high);
    }
  }

  @Override
  public void dispose() {
    highLevelStack.clear();
    bashSolverContext.dispose();
  }
  
}
