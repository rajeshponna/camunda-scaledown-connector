package io.github.rajeshponna.scaledown;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.connector.api.annotation.OutboundConnector;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.api.outbound.OutboundConnectorFunction;
import io.github.rajeshponna.scaledown.client.ScaleDownClient;
import io.github.rajeshponna.scaledown.client.ScaleDownErrors;
import io.github.rajeshponna.scaledown.document.DocumentEncoder;
import io.github.rajeshponna.scaledown.model.Operation;
import io.github.rajeshponna.scaledown.model.ScaleDownRequest;
import io.github.rajeshponna.scaledown.operation.ClassifyOperation;
import io.github.rajeshponna.scaledown.operation.CompressOperation;
import io.github.rajeshponna.scaledown.operation.DecisionsOperation;
import io.github.rajeshponna.scaledown.operation.ExtractOperation;
import io.github.rajeshponna.scaledown.operation.ScaleDownOperation;
import io.github.rajeshponna.scaledown.operation.SummarizeOperation;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@OutboundConnector(
    name = "ScaleDown",
    inputVariables = {
      "operation",
      "authentication",
      "input",
      "classify",
      "decisions",
      "extract",
      "summarize",
      "compress",
      "review",
      "connection"
    },
    type = ScaleDownConnectorFunction.TYPE)
public class ScaleDownConnectorFunction implements OutboundConnectorFunction {

  public static final String TYPE = "io.github.rajeshponna:scaledown:1";
  static final int DEFAULT_TIMEOUT_SECONDS = 120;
  static final int MAX_TIMEOUT_SECONDS = 600;

  private final HttpClient httpClient;
  private final ObjectMapper mapper;
  private final DocumentEncoder encoder;
  private final Map<Operation, ScaleDownOperation> operations = new EnumMap<>(Operation.class);

  /** Used by the Connectors runtime (SPI). */
  public ScaleDownConnectorFunction() {
    this(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
        new DocumentEncoder());
  }

  public ScaleDownConnectorFunction(HttpClient httpClient, ObjectMapper mapper, DocumentEncoder encoder) {
    this.httpClient = httpClient;
    this.mapper = mapper;
    this.encoder = encoder;
    operations.put(Operation.CLASSIFY, new ClassifyOperation());
    operations.put(Operation.DECISIONS, new DecisionsOperation());
    operations.put(Operation.EXTRACT, new ExtractOperation());
    operations.put(Operation.SUMMARIZE, new SummarizeOperation());
    operations.put(Operation.COMPRESS, new CompressOperation());
  }

  @Override
  public Object execute(OutboundConnectorContext context) {
    ScaleDownRequest request = bind(context);
    return execute(request);
  }

  /** Separated from {@link #execute(OutboundConnectorContext)} so it can be tested without a runtime. */
  public Map<String, Object> execute(ScaleDownRequest request) {
    if (request.operation() == null) {
      throw ScaleDownErrors.fatal(ScaleDownErrors.INVALID_INPUT, "Operation is required.");
    }
    String apiKey = request.authentication() == null ? null : request.authentication().apiKey();
    if (apiKey == null || apiKey.isBlank()) {
      throw ScaleDownErrors.fatal(
          ScaleDownErrors.INVALID_INPUT,
          "API key is required. Set it to {{secrets.SCALEDOWN_API_KEY}} and define the secret in your Connectors runtime.");
    }
    Double min = request.reviewOrEmpty().minConfidence();
    if (min != null && (min < 0 || min > 1)) {
      throw ScaleDownErrors.fatal(
          ScaleDownErrors.INVALID_INPUT, "Minimum confidence must be between 0 and 1; got " + min + ".");
    }

    ScaleDownRequest.ConnectionOptions conn = request.connectionOrEmpty();
    ScaleDownClient client =
        new ScaleDownClient(httpClient, mapper, conn.baseUrl(), apiKey.trim(), timeout(conn.timeoutSeconds()));

    ScaleDownOperation.Result result =
        operations.get(request.operation()).execute(request, client, encoder);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("operation", request.operation().value());
    response.putAll(result.response());
    List<String> low = result.lowConfidence();
    boolean needsReview = !low.isEmpty();
    response.put("needsReview", needsReview);
    response.put("lowConfidence", low);

    if (needsReview && request.reviewOrEmpty().failOnLowConfidence()) {
      Map<String, Object> vars = new LinkedHashMap<>();
      vars.put("lowConfidence", low);
      vars.put("minConfidence", min);
      throw ScaleDownErrors.fatal(
          ScaleDownErrors.LOW_CONFIDENCE,
          "ScaleDown " + request.operation().value() + " result is below the minimum confidence "
              + min + " for: " + String.join(", ", low),
          null,
          vars);
    }
    return response;
  }

  private ScaleDownRequest bind(OutboundConnectorContext context) {
    try {
      return context.bindVariables(ScaleDownRequest.class);
    } catch (RuntimeException e) {
      String message = e.getMessage();
      Throwable root = e;
      while (root.getCause() != null && root.getCause() != root) {
        root = root.getCause();
      }
      if (root instanceof IllegalArgumentException && root.getMessage() != null) {
        message = root.getMessage();
      }
      throw ScaleDownErrors.fatal(
          ScaleDownErrors.INVALID_INPUT, "Invalid connector input: " + message, e, Map.of());
    }
  }

  static Duration timeout(Integer seconds) {
    int s = seconds == null ? DEFAULT_TIMEOUT_SECONDS : seconds;
    if (s < 1 || s > MAX_TIMEOUT_SECONDS) {
      throw ScaleDownErrors.fatal(
          ScaleDownErrors.INVALID_INPUT,
          "Timeout must be between 1 and " + MAX_TIMEOUT_SECONDS + " seconds; got " + s + ".");
    }
    return Duration.ofSeconds(s);
  }
}
