package gov.nasa.jpf.jdart.solvers.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.io.IOException;

import com.google.gson.JsonObject;

import gov.nasa.jpf.JPF;
import gov.nasa.jpf.constraints.api.ConstraintSolver.Result;
import gov.nasa.jpf.constraints.api.Expression;
import gov.nasa.jpf.constraints.api.SolverContext;
import gov.nasa.jpf.constraints.api.Valuation;
import gov.nasa.jpf.constraints.api.Variable;
import gov.nasa.jpf.constraints.expressions.AbstractExpressionVisitor;
import gov.nasa.jpf.constraints.expressions.IsExactTypeExpression;
import gov.nasa.jpf.constraints.expressions.PropositionalCompound;
import gov.nasa.jpf.constraints.util.ExpressionUtil;
import gov.nasa.jpf.jdart.solvers.llm.LLMSolverClient.LLMSolverResponse;
import gov.nasa.jpf.util.JPFLogger;
import gov.nasa.jpf.vm.ThreadInfo;
import gov.nasa.jpf.vm.VM;

public class LLMEnhancedSolverContext extends SolverContext {

  private final SolverContext baseSolverContext;
  private final LLMSolverClient llmClient;
  private final HeapStateCollector heapCollector;
  private final SourceContextCollector sourceCollector;
  private JPFLogger logger = JPF.getLogger("jdart");

  /**
   * Stack of high-level constraints per push/pop scope. Each push() creates a
   * new, empty list on the stack. pop(n) removes the top n scopes.
   */
  private final Deque<List<Expression<Boolean>>> highLevelStack = new ArrayDeque<>();
  private final Deque<Map<String, Variable<?>>> hlFreeVarsStack = new ArrayDeque<Map<String, Variable<?>>>();

  public LLMEnhancedSolverContext(SolverContext baseSolverContext) {
    this.baseSolverContext = baseSolverContext;
    this.llmClient = LLMSolverClient.createDefault();
    this.heapCollector = HeapStateCollector.createDefault();
    this.sourceCollector = SourceContextCollector.createDefault();
    // initialize base scope for high-level constraints
    // this.highLevelStack.push(new ArrayList<>());
    this.hlFreeVarsStack.push(new HashMap<String, Variable<?>>());
  }

  @Override
  public void push() {
    baseSolverContext.push();
    highLevelStack.push(new ArrayList<>());
    Map<String, Variable<?>> fvMap = hlFreeVarsStack.peek();
    hlFreeVarsStack.push(new HashMap<String, Variable<?>>(fvMap));
  }

  @Override
  public void pop(int n) {
    baseSolverContext.pop(n);
    for (int i = 0; i < n; i++) {
      // for (int j = 0; j < highLevelStack.peek().size(); j++) {
      // System.out.println("high-level constraint " + j + ": " +
      // highLevelStack.peek().get(j));
      // }
      highLevelStack.pop();
      hlFreeVarsStack.pop();
    }
  }

  @Override
  public void add(List<Expression<Boolean>> expressions) {
    if (expressions == null || expressions.isEmpty()) {
      return;
    }

    List<Expression<Boolean>> normal = new ArrayList<>();
    List<Expression<Boolean>> high = new ArrayList<>();

    Map<String, Variable<?>> fvMap = hlFreeVarsStack.peek();

    for (Expression<Boolean> e : expressions) {
      if (e != null && containsHighLevel(e)) {
        high.add(e);
        Set<Variable<?>> fvs = ExpressionUtil.freeVariables(e);
        for (Variable<?> v : fvs) {
          fvMap.put(v.getName(), v);
        }
      } else {
        normal.add(e);
      }
      // Set<Variable<?>> fvs = ExpressionUtil.freeVariables(e);
      // for (Variable<?> v : fvs) {
      // fvMap.put(v.getName(), v);
      // }

    }

    // Forward normal constraints to the base solver immediately
    if (!normal.isEmpty()) {
      baseSolverContext.add(normal);
    }

    // Store high-level constraints in the current high-level scope
    if (!high.isEmpty()) {
      List<Expression<Boolean>> current = highLevelStack.peek();
      // if (current == null) {
      // current = new ArrayList<>();
      // highLevelStack.push(current);
      // }
      current.addAll(high);
    }
  }

  @Override
  public void dispose() {
    highLevelStack.clear();
    baseSolverContext.dispose();
    hlFreeVarsStack.clear();
  }

  @Override
  public Result solve(Valuation val) {
    // If there are no high-level constraints in any scope, delegate to base solver.
    boolean hasHighLevel = highLevelStack.stream().anyMatch(list -> !list.isEmpty());
    if (!hasHighLevel) {
      return baseSolverContext.solve(val);
    }

    // First, solve normal (non-high-level) constraints using base solver
    Result baseResult = baseSolverContext.solve(val);
    if (baseResult != Result.SAT) {
      logger.finer("Base constraints are UNSAT, returning UNSAT");
      return baseResult;
    }

    List<Expression<Boolean>> hlExpressions = highLevelStack.stream()
        .flatMap(List::stream)
        .collect(Collectors.toList());

    System.out.println("+++++++++++++++++++++++++++++");
    logger.finer("Solving with " + hlExpressions.size() + " high-level constraints");
    logger.finer("hlExpressions: " + hlExpressions);
    System.out.println("+++++++++++++++++++++++++++++");

    if (hlExpressions.isEmpty()) {
      return baseResult;
    }

    // Collect all constraints from the constraints tree (not just current path)
    // This ensures we check unreachable expressions across all decision points
    // For example, when exploring getAge()'s Cat branch, we should also consider
    // makeSound()'s Cat constraint (which is reachable) even though makeSound()
    // hasn't been reached on that path yet.
    List<Expression<Boolean>> allTreeConstraints = new ArrayList<>(hlExpressions);
    try {
      ThreadInfo ti = VM.getVM().getCurrentThread();
      if (ti != null) {
        gov.nasa.jpf.jdart.ConcolicMethodExplorer currentAnalysis = 
            gov.nasa.jpf.jdart.ConcolicMethodExplorer.getCurrentAnalysis(ti);
        if (currentAnalysis != null) {
          gov.nasa.jpf.jdart.constraints.InternalConstraintsTree constraintsTree = 
              currentAnalysis.getInternalConstraintsTree();
          if (constraintsTree != null) {
            List<Expression<Boolean>> treeConstraints = constraintsTree.getAllDecisionConstraints();
            // Add all constraints from tree (duplicates are OK, checkUnreachableExpressions handles them)
            allTreeConstraints.addAll(treeConstraints);
          }
        }
      }
    } catch (Exception e) {
      logger.warning("Failed to collect constraints from constraints tree: " + e.getMessage());
    }

    // Process unreachable IsExactTypeExpression constraints using all constraints
    Result unreachableCheck = checkUnreachableExpressions(allTreeConstraints);
    if (unreachableCheck != Result.SAT) {
      logger.finer("Unreachable expression check returned: " + unreachableCheck);
      return unreachableCheck;
    }

    // Print all symbolic variables' current values
    // if (val != null && !val.getVariables().isEmpty()) {
    //   logger.finer("Current valuation before solving:");
    //   for (ValuationEntry<?> entry : val.entries()) {
    //     Variable<?> var = entry.getVariable();
    //     Object value = entry.getValue();
    //     logger.finer("  " + var.getName() + " (" + var.getType() + ") = " + value);
    //   }
    // } else {
    //   logger.finer("Current valuation is empty or null");
    // }

    // Filter out redundant unreachable expressions from current path constraints
    hlExpressions = filterRedundantUnreachableExpressions(hlExpressions);

    if (checkIsExactTypeConflicts(hlExpressions)) {
      logger.finer("IsExactType conflicts detected, returning UNSAT");
      return Result.UNSAT;
    }

    try {
      // Collect heap state, parameter type constraints, and source context from current execution context
      JsonObject heapState = null;
      Map<String, String> parameterTypeConstraints = new HashMap<>();
      JsonObject sourceContext = null;
      
      try {
        ThreadInfo ti = VM.getVM().getCurrentThread();
        if (ti != null) {
          // Get parameter type constraints from the current analysis
          gov.nasa.jpf.jdart.ConcolicMethodExplorer currentAnalysis = 
              gov.nasa.jpf.jdart.ConcolicMethodExplorer.getCurrentAnalysis(ti);
          if (currentAnalysis != null) {
            parameterTypeConstraints = currentAnalysis.getParameterTypeConstraints();
            logger.finer("Collected parameter type constraints: " + parameterTypeConstraints);
          }
          
          // Collect source context (prefer the target method if available)
          sourceContext = sourceCollector.collectSourceContext(ti, currentAnalysis, hlExpressions, parameterTypeConstraints);
          if (sourceContext != null) {
            logger.finer("Collected source context for method: " + 
                sourceContext.get("method_name").getAsString());
          }
          
          // Pass high-level expressions for constraint-aware heap slicing
          heapState = heapCollector.collectHeapState(ti, val, hlExpressions);
          if (heapState != null) {
            JsonObject objects = heapState.getAsJsonObject("objects");
            JsonObject bindings = heapState.getAsJsonObject("bindings");
            int objectCount = objects != null ? objects.entrySet().size() : 0;
            int bindingsCount = bindings != null ? bindings.entrySet().size() : 0;
            
            logger.finer("Collected heap state with " + objectCount + " reachable objects and " +
                bindingsCount + " bindings");
            logger.finer("Heap state: " + heapState.toString());
          }
        }
      } catch (Exception e) {
        logger.warning("Failed to collect heap state, parameter types, or source context: " + e.getMessage());
        // Continue without heap state, parameter types, or source context
      }

      // Send request to LLM solver with parameter type constraints and source context
      LLMSolverResponse response = llmClient.solve(hlExpressions, heapState, parameterTypeConstraints, sourceContext);

      // Update valuation if SAT
      if (response.getResult() == Result.SAT && response.getValuationArray() != null && val != null) {
        Map<String, Variable<?>> varNameToVar = buildVariableNameMap();
        LLMValuationHandler valuationHandler = new LLMValuationHandler(varNameToVar);
        valuationHandler.updateValuationFromLlmResponse(response.getValuationArray(), val);
      }

      logger.finer("LLM solver result: " + response.getResult());

      return response.getResult();
    } catch (IOException e) {
      // If the LLM service is unreachable, fall back to base solver's result.
      logger.warning("LLM solver call failed: " + e.getMessage());
      return baseResult;
    }
  }

  private Boolean checkIsExactTypeConflicts(List<Expression<Boolean>> expressions) {
    String typeSignature = null;
    for (Expression<Boolean> expr : expressions) {
      if (expr instanceof IsExactTypeExpression) {
        IsExactTypeExpression isExactTypeExpr = (IsExactTypeExpression) expr;
        if (typeSignature == null) {
          typeSignature = isExactTypeExpr.getTypeSignature();
        } else if (!typeSignature.equals(isExactTypeExpr.getTypeSignature())) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Build a map from variable name to Variable object.
   * Includes free variables from high-level constraints.
   */
  private Map<String, Variable<?>> buildVariableNameMap() {
    Map<String, Variable<?>> varNameToVar = new HashMap<String, Variable<?>>();

    // Add free variables from high-level constraints (in case they're not in the
    // valuation yet)
    Map<String, Variable<?>> hlFreeVars = hlFreeVarsStack.peek();
    if (hlFreeVars != null) {
      varNameToVar.putAll(hlFreeVars);
    }

    return varNameToVar;
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

  /**
   * Check if all IsExactTypeExpression constraints for any type are unreachable.
   * If so, return UNSAT. Otherwise return SAT.
   */
  private Result checkUnreachableExpressions(List<Expression<Boolean>> expressions) {
    // Collect all IsExactTypeExpression instances
    IsExactTypeCollector collector = new IsExactTypeCollector();
    for (Expression<Boolean> expr : expressions) {
      expr.accept(collector, null);
    }

    // Group by type signature
    Map<String, List<IsExactTypeExpression>> byType = collector.getExpressionsByType();

    // Check each type: if all expressions for a type are unreachable, return UNSAT
    for (Map.Entry<String, List<IsExactTypeExpression>> entry : byType.entrySet()) {
      String typeSignature = entry.getKey();
      List<IsExactTypeExpression> exprs = entry.getValue();
      
      boolean allUnreachable = true;
      for (IsExactTypeExpression expr : exprs) {
        if (!expr.isUnreachable()) {
          allUnreachable = false;
          break;
        }
      }
      
      if (allUnreachable && !exprs.isEmpty()) {
        logger.finer("All IsExactTypeExpression constraints for type " + typeSignature + " are unreachable, returning UNSAT");
        return Result.UNSAT;
      }
    }

    return Result.SAT;
  }

  /**
   * Filter out redundant unreachable expressions.
   * For each type, if there's at least one reachable expression, remove all unreachable ones.
   */
  private List<Expression<Boolean>> filterRedundantUnreachableExpressions(List<Expression<Boolean>> expressions) {
    // Collect all IsExactTypeExpression instances
    IsExactTypeCollector collector = new IsExactTypeCollector();
    for (Expression<Boolean> expr : expressions) {
      expr.accept(collector, null);
    }

    // Group by type signature
    Map<String, List<IsExactTypeExpression>> byType = collector.getExpressionsByType();

    // For each type, if there's at least one reachable expression, mark unreachable ones for removal
    Set<IsExactTypeExpression> toRemove = new HashSet<>();
    for (Map.Entry<String, List<IsExactTypeExpression>> entry : byType.entrySet()) {
      List<IsExactTypeExpression> exprs = entry.getValue();
      
      boolean hasReachable = false;
      for (IsExactTypeExpression expr : exprs) {
        if (!expr.isUnreachable()) {
          hasReachable = true;
          break;
        }
      }
      
      // If there's at least one reachable, remove all unreachable ones
      if (hasReachable) {
        for (IsExactTypeExpression expr : exprs) {
          if (expr.isUnreachable()) {
            toRemove.add(expr);
          }
        }
      }
    }

    // If nothing to remove, return original list
    if (toRemove.isEmpty()) {
      return expressions;
    }

    // Create a visitor to remove unreachable expressions
    UnreachableFilterVisitor filterVisitor = new UnreachableFilterVisitor(toRemove);
    List<Expression<Boolean>> filtered = new ArrayList<>();
    for (Expression<Boolean> expr : expressions) {
      Expression<Boolean> filteredExpr = expr.accept(filterVisitor, null);
      if (filteredExpr != null) {
        filtered.add(filteredExpr);
      }
    }

    logger.finer("Filtered out " + (expressions.size() - filtered.size()) + " redundant unreachable expressions");
    return filtered;
  }

  /**
   * Visitor to collect all IsExactTypeExpression instances from an expression tree.
   */
  private static class IsExactTypeCollector extends AbstractExpressionVisitor<Void, Void> {
    private final List<IsExactTypeExpression> expressions = new ArrayList<>();

    @Override
    public <E> Void visit(IsExactTypeExpression isExactTypeExpr, Void data) {
      expressions.add(isExactTypeExpr);
      // Continue visiting children
      Expression<?>[] children = isExactTypeExpr.getChildren();
      if (children != null) {
        for (Expression<?> child : children) {
          child.accept(this, data);
        }
      }
      return null;
    }

    @Override
    protected <E> Void defaultVisit(Expression<E> expression, Void data) {
      // For other expression types, continue visiting children to find IsExactTypeExpression
      Expression<?>[] children = expression.getChildren();
      if (children != null) {
        for (Expression<?> child : children) {
          child.accept(this, data);
        }
      }
      return null;
    }

    public Map<String, List<IsExactTypeExpression>> getExpressionsByType() {
      Map<String, List<IsExactTypeExpression>> byType = new HashMap<>();
      for (IsExactTypeExpression expr : expressions) {
        String typeSig = expr.getTypeSignature();
        byType.computeIfAbsent(typeSig, k -> new ArrayList<>()).add(expr);
      }
      return byType;
    }
  }

  /**
   * Visitor to filter out unreachable IsExactTypeExpression instances.
   * Replaces expressions containing only unreachable IsExactTypeExpression with null (to be removed).
   */
  private static class UnreachableFilterVisitor extends AbstractExpressionVisitor<Expression<Boolean>, Void> {
    private final Set<IsExactTypeExpression> toRemove;

    public UnreachableFilterVisitor(Set<IsExactTypeExpression> toRemove) {
      this.toRemove = toRemove;
    }

    @Override
    public <E> Expression<Boolean> visit(IsExactTypeExpression isExactTypeExpr, Void data) {
      if (toRemove.contains(isExactTypeExpr)) {
        // Return FALSE constant to effectively remove this constraint
        return ExpressionUtil.FALSE;
      }
      return isExactTypeExpr;
    }

    @Override
    public Expression<Boolean> visit(PropositionalCompound n, Void data) {
      Expression<Boolean> left = n.getLeft().accept(this, data);
      Expression<Boolean> right = n.getRight().accept(this, data);
      
      // If either side is FALSE (removed), simplify
      if (left == ExpressionUtil.FALSE || right == ExpressionUtil.FALSE) {
        if (n.getOperator() == gov.nasa.jpf.constraints.expressions.LogicalOperator.AND) {
          return ExpressionUtil.FALSE;
        } else if (n.getOperator() == gov.nasa.jpf.constraints.expressions.LogicalOperator.OR) {
          if (left == ExpressionUtil.FALSE) {
            return right;
          } else {
            return left;
          }
        }
      }
      
      if (left == n.getLeft() && right == n.getRight()) {
        return n;
      }
      return new PropositionalCompound(left, n.getOperator(), right);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <E> Expression<Boolean> defaultVisit(Expression<E> expression, Void data) {
      // For other expressions, visit children but keep structure
      Expression<?>[] children = expression.getChildren();
      if (children == null || children.length == 0) {
        return (Expression<Boolean>) expression;
      }
      
      Expression<?>[] newChildren = new Expression[children.length];
      boolean changed = false;
      for (int i = 0; i < children.length; i++) {
        Expression<?> newChild = children[i].accept(this, data);
        if (newChild != children[i]) {
          changed = true;
        }
        newChildren[i] = newChild;
      }
      
      if (changed) {
        return (Expression<Boolean>) expression.duplicate(newChildren);
      }
      return (Expression<Boolean>) expression;
    }
  }

}
