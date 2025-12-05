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
import gov.nasa.jpf.constraints.api.Valuation;
import gov.nasa.jpf.constraints.api.ValuationEntry;

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
   * Solve high-level constraints using the LLM solver service.
   * 
   * @param hlExpressions High-level constraint expressions to solve
   * @param val Current valuation (may be null)
   * @return LLM solver response containing result and optional valuation
   * @throws IOException if communication with LLM service fails
   */
  public LLMSolverResponse solve(List<Expression<Boolean>> hlExpressions, Valuation val) throws IOException {
    String payload = buildJsonPayload(hlExpressions, val);
    String responseBody = sendLlmRequest(payload);
    
    if (responseBody == null) {
      return new LLMSolverResponse(Result.DONT_KNOW, null);
    }
    
    return parseLlmResponse(responseBody);
  }

  /**
   * Build JSON payload from high-level expressions and valuation.
   * Uses Gson for proper JSON serialization with automatic escaping.
   */
  private String buildJsonPayload(List<Expression<Boolean>> hlExpressions, Valuation val) {
    JsonObject payload = new JsonObject();

    // Build constraints array
    JsonArray constraintsArray = new JsonArray();
    for (Expression<Boolean> expr : hlExpressions) {
      constraintsArray.add(new JsonPrimitive(expr.toString()));
    }
    payload.add("constraints", constraintsArray);

    // Build valuation object
    if (val == null) {
      payload.add("valuation", null);
    } else {
      JsonObject valuationObj = new JsonObject();
      for (ValuationEntry<?> entry : val.entries()) {
        String varName = entry.getVariable().getName();
        Object value = entry.getValue();
        
        // Convert value to appropriate JsonElement
        if (value == null) {
          valuationObj.add(varName, null);
        } else if (value instanceof String) {
          valuationObj.addProperty(varName, (String) value);
        } else if (value instanceof Number) {
          // Handle different number types
          if (value instanceof Integer) {
            valuationObj.addProperty(varName, (Integer) value);
          } else if (value instanceof Long) {
            valuationObj.addProperty(varName, (Long) value);
          } else if (value instanceof Double) {
            valuationObj.addProperty(varName, (Double) value);
          } else if (value instanceof Float) {
            valuationObj.addProperty(varName, (Float) value);
          } else {
            // For other number types, convert to string
            valuationObj.addProperty(varName, value.toString());
          }
        } else if (value instanceof Boolean) {
          valuationObj.addProperty(varName, (Boolean) value);
        } else {
          // For other types, convert to string
          valuationObj.addProperty(varName, value.toString());
        }
      }
      payload.add("valuation", valuationObj);
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
