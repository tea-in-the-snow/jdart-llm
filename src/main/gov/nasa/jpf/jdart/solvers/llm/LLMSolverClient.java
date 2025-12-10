package gov.nasa.jpf.jdart.solvers.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import gov.nasa.jpf.constraints.api.ConstraintSolver.Result;
import gov.nasa.jpf.constraints.api.Expression;

/**
 * Client for communicating with the LLM solver service.
 * Handles HTTP communication, JSON payload construction, and response parsing.
 */
public class LLMSolverClient {

  private static final Gson gson = new Gson();
  
  private final SolverConfig config;

  /**
   * Configuration for LLM solver connection.
   */
  public static class SolverConfig {
    final String url;
    final int timeoutSeconds;

    public SolverConfig(String url, int timeoutSeconds) {
      this.url = url;
      this.timeoutSeconds = timeoutSeconds;
    }
  }

  /**
   * Response from LLM solver service.
   */
  public static class LLMSolverResponse {
    private final Result result;
    private final JsonArray valuationArray;

    public LLMSolverResponse(Result result, JsonArray valuationArray) {
      this.result = result;
      this.valuationArray = valuationArray;
    }

    public Result getResult() {
      return result;
    }

    public JsonArray getValuationArray() {
      return valuationArray;
    }
  }

  public LLMSolverClient(SolverConfig config) {
    this.config = config;
  }

  /**
   * Create a default solver client using environment variables.
   * The service URL can be configured via LLM_SOLVER_URL (default:
   * http://127.0.0.1:8000/solve).
   * The request timeout can be configured via LLM_SOLVER_TIMEOUT (default: 60s).
   */
  public static LLMSolverClient createDefault() {
    String solverUrl = System.getenv("LLM_SOLVER_URL");
    if (solverUrl == null || solverUrl.isEmpty()) {
      solverUrl = "http://127.0.0.1:8000/solve";
    }

    int timeoutSeconds = 60;
    String timeoutEnv = System.getenv("LLM_SOLVER_TIMEOUT");
    if (timeoutEnv != null && !timeoutEnv.isEmpty()) {
      try {
        timeoutSeconds = Integer.parseInt(timeoutEnv);
      } catch (NumberFormatException nfe) {
        System.err.println(
            "Invalid LLM_SOLVER_TIMEOUT value '" + timeoutEnv + "', using default " + timeoutSeconds + "s");
      }
    }

    return new LLMSolverClient(new SolverConfig(solverUrl, timeoutSeconds));
  }

  /**
   * Solve high-level constraints using LLM service.
   * Sends HTTP POST request with JSON payload and parses response.
   * 
   * @param hlExpressions High-level constraint expressions to solve
   * @param heapState Heap state information (may be null)
   * @param parameterTypeConstraints Parameter type constraints from method signature
   * @param sourceContext Source code context for the method being analyzed (may be null)
   * @return LLM solver response containing result and optional valuation
   * @throws IOException if communication with LLM service fails
   */
  public LLMSolverResponse solve(List<Expression<Boolean>> hlExpressions, JsonObject heapState, 
                                 java.util.Map<String, String> parameterTypeConstraints,
                                 JsonObject sourceContext) throws IOException {
    String payload = buildJsonPayload(hlExpressions, heapState, parameterTypeConstraints, sourceContext);
    String responseBody = sendLlmRequest(payload);
    
    if (responseBody == null) {
      return new LLMSolverResponse(Result.DONT_KNOW, null);
    }
    
    return parseLlmResponse(responseBody);
  }

  /**
   * Solve high-level constraints using the LLM solver service with parameter type constraints (backward compatibility).
   * 
   * @param hlExpressions High-level constraint expressions to solve
   * @param heapState Heap state information (may be null)
   * @param parameterTypeConstraints Map from parameter names to their static types (may be null)
   * @return LLM solver response containing result and optional valuation
   * @throws IOException if communication with LLM service fails
   */
  public LLMSolverResponse solve(List<Expression<Boolean>> hlExpressions, JsonObject heapState, 
                                 java.util.Map<String, String> parameterTypeConstraints) throws IOException {
    return solve(hlExpressions, heapState, parameterTypeConstraints, null);
  }
  
  /**
   * Solve high-level constraints without parameter type constraints (backward compatibility).
   * 
   * @param hlExpressions High-level constraint expressions to solve
   * @param heapState Heap state information (may be null)
   * @return LLM solver response containing result and optional valuation
   * @throws IOException if communication with LLM service fails
   */
  public LLMSolverResponse solve(List<Expression<Boolean>> hlExpressions, JsonObject heapState) throws IOException {
    return solve(hlExpressions, heapState, null, null);
  }
  
  /**
   * Solve high-level constraints without heap state (backward compatibility).
   * 
   * @param hlExpressions High-level constraint expressions to solve
   * @return LLM solver response containing result and optional valuation
   * @throws IOException if communication with LLM service fails
   */
  public LLMSolverResponse solve(List<Expression<Boolean>> hlExpressions) throws IOException {
    return solve(hlExpressions, null, null, null);
  }

  /**
  * Build JSON payload from high-level expressions, heap state, parameter type constraints, and source context.
   * Uses Gson for proper JSON serialization with automatic escaping.
   * 
   * The heap_state structure includes:
   * - bindings: mapping from constraint reference names (e.g., "node(ref)") to object IDs
   * - objects: heap objects with their fields (sliced to relevant objects only)
   * - modifiable_objects: list of object IDs that can be modified by the solver
   * - allowed_to_allocate: whether new objects can be allocated
   * - schemas: class field schemas for LLM understanding
   * 
   * The parameter_type_constraints provides implicit type constraints:
   * - Maps parameter names to their static declared types (e.g., "node" -> "ListNode")
   * - Informs the LLM about polymorphic type constraints (actual type must be subtype of declared type)
   * 
   * The source_context provides source code for better LLM understanding:
   * - method_source: source code of the method being analyzed
   * - class_source: full source code of the class
   * - line_numbers: line number information for the method
   */
  private String buildJsonPayload(List<Expression<Boolean>> hlExpressions, JsonObject heapState,
                                   java.util.Map<String, String> parameterTypeConstraints,
                                   JsonObject sourceContext) {
    JsonObject payload = new JsonObject();

    // Build constraints array
    JsonArray constraintsArray = new JsonArray();
    for (Expression<Boolean> expr : hlExpressions) {
      constraintsArray.add(new JsonPrimitive(expr.toString()));
    }
    payload.add("constraints", constraintsArray);

    // Add heap state if available
    if (heapState != null && heapState.entrySet().size() > 0) {
      payload.add("heap_state", heapState);
    }

    // Add parameter type constraints if available
    if (parameterTypeConstraints != null && !parameterTypeConstraints.isEmpty()) {
      JsonObject paramTypesJson = new JsonObject();
      for (java.util.Map.Entry<String, String> entry : parameterTypeConstraints.entrySet()) {
        paramTypesJson.addProperty(entry.getKey(), entry.getValue());
      }
      payload.add("parameter_type_constraints", paramTypesJson);
    }

    // Add source context if available
    if (sourceContext != null && sourceContext.entrySet().size() > 0) {
      payload.add("source_context", sourceContext);
    }

    // Add optional hint field
    payload.addProperty("hint", "java-jdart-llm-high-level-constraints");

    return gson.toJson(payload);
  }

  /**
   * Send HTTP POST request to LLM solver and return response body.
   * Returns null if request fails or response is invalid.
   */
  private String sendLlmRequest(String payload) throws IOException {
    URL url = new URL(config.url);
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("POST");
    connection.setRequestProperty("Content-Type", "application/json");
    connection.setDoOutput(true);
    connection.setConnectTimeout(config.timeoutSeconds * 1000);
    connection.setReadTimeout(config.timeoutSeconds * 1000);

    // Write the payload
    try (OutputStream os = connection.getOutputStream()) {
      byte[] input = payload.getBytes("utf-8");
      os.write(input, 0, input.length);
    }

    int statusCode = connection.getResponseCode();
    if (statusCode / 100 != 2) {
      System.err.println("LLM solver returned non-2xx status: " + statusCode);
      connection.disconnect();
      return null;
    }

    // Read the response
    StringBuilder responseBody = new StringBuilder();
    try (BufferedReader br = new BufferedReader(
        new InputStreamReader(connection.getInputStream(), "utf-8"))) {
      String responseLine;
      while ((responseLine = br.readLine()) != null) {
        responseBody.append(responseLine.trim());
      }
    }
    connection.disconnect();

    String body = responseBody.toString();
    if (body == null || body.isEmpty()) {
      System.err.println("LLM solver returned empty body");
      return null;
    }

    return body;
  }

  /**
   * Parse LLM solver response.
   * Returns the appropriate Result and valuation array (if available).
   */
  private LLMSolverResponse parseLlmResponse(String body) {
    try {
      JsonObject jsonObject = new JsonParser().parse(body).getAsJsonObject();
      
      // Get result field
      if (!jsonObject.has("result")) {
        System.err.println("LLM solver response missing 'result' field, body: " + body);
        return new LLMSolverResponse(Result.DONT_KNOW, null);
      }
      
      String resultStr = jsonObject.get("result").getAsString().toUpperCase();
      
      Result result;
      if ("SAT".equals(resultStr)) {
        result = Result.SAT;
      } else if ("UNSAT".equals(resultStr)) {
        result = Result.UNSAT;
      } else if ("UNKNOWN".equals(resultStr) || "DONT_KNOW".equals(resultStr)) {
        result = Result.DONT_KNOW;
      } else {
        System.err.println("LLM solver returned unknown result value: " + resultStr);
        result = Result.DONT_KNOW;
      }

      // Parse the valuation array from the body if SAT
      JsonArray llmValuationArray = null;
      if (result == Result.SAT && jsonObject.has("valuation") && !jsonObject.get("valuation").isJsonNull()) {
        llmValuationArray = jsonObject.getAsJsonArray("valuation");
      }

      return new LLMSolverResponse(result, llmValuationArray);
    } catch (Exception e) {
      System.err.println("Failed to parse LLM solver response: " + e.getMessage());
      System.err.println("Response body: " + body);
      e.printStackTrace();
      return new LLMSolverResponse(Result.DONT_KNOW, null);
    }
  }
}
