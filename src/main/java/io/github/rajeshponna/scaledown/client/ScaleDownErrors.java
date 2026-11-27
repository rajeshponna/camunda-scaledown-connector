package io.github.rajeshponna.scaledown.client;

import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.error.ConnectorRetryException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error codes the connector raises. Use them in the template's error expression, e.g.
 * {@code if error.code = "SCALEDOWN_LOW_CONFIDENCE" then bpmnError("LOW_CONFIDENCE", error.message) else null}.
 *
 * <p>Errors that will never succeed on retry (bad input, bad key, no credits) set retries to 0 so
 * the job fails fast instead of burning retries and credits. Transient errors keep the job's normal
 * retry count.
 */
public final class ScaleDownErrors {

  public static final String INVALID_INPUT = "SCALEDOWN_INVALID_INPUT";
  public static final String INVALID_REQUEST = "SCALEDOWN_INVALID_REQUEST";
  public static final String UNAUTHORIZED = "SCALEDOWN_UNAUTHORIZED";
  public static final String INSUFFICIENT_CREDITS = "SCALEDOWN_INSUFFICIENT_CREDITS";
  public static final String RATE_LIMITED = "SCALEDOWN_RATE_LIMITED";
  public static final String UNAVAILABLE = "SCALEDOWN_UNAVAILABLE";
  public static final String TIMEOUT = "SCALEDOWN_TIMEOUT";
  public static final String INVALID_RESPONSE = "SCALEDOWN_INVALID_RESPONSE";
  public static final String UNSUPPORTED_DOCUMENT = "SCALEDOWN_UNSUPPORTED_DOCUMENT";
  public static final String LOW_CONFIDENCE = "SCALEDOWN_LOW_CONFIDENCE";

  private static final int MAX_BODY_CHARS = 2000;

  private ScaleDownErrors() {}

  /** Fails immediately (no retries). */
  public static ConnectorRetryException fatal(String code, String message) {
    return fatal(code, message, null, Map.of());
  }

  public static ConnectorRetryException fatal(
      String code, String message, Throwable cause, Map<String, Object> variables) {
    return withVariables(
        ConnectorRetryException.builder()
            .errorCode(code)
            .message(message)
            .cause(cause)
            .retries(0)
            .build(),
        variables);
  }

  /** Uses the job's remaining retries; an explicit backoff (e.g. Retry-After) wins over the template's. */
  public static ConnectorRetryException retryable(
      String code, String message, Throwable cause, Duration backoff) {
    return ConnectorRetryException.builder()
        .errorCode(code)
        .message(message)
        .cause(cause)
        .backoffDuration(backoff)
        .build();
  }

  public static ConnectorException forHttpStatus(int status, String body, Duration retryAfter) {
    String snippet = truncate(body);
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("httpStatus", status);
    vars.put("responseBody", snippet);
    String detail = " (HTTP " + status + "): " + snippet;

    return switch (status) {
      case 400, 404, 413, 422 ->
          fatal(INVALID_REQUEST, "ScaleDown rejected the request" + detail, null, vars);
      case 401, 403 ->
          fatal(UNAUTHORIZED, "ScaleDown rejected the API key" + detail, null, vars);
      case 402 ->
          fatal(INSUFFICIENT_CREDITS, "ScaleDown account is out of credits" + detail, null, vars);
      case 429 ->
          withVariables(
              retryable(RATE_LIMITED, "ScaleDown rate limit exceeded" + detail, null, retryAfter),
              vars);
      case 504 ->
          withVariables(retryable(TIMEOUT, "ScaleDown timed out" + detail, null, retryAfter), vars);
      default -> {
        if (status >= 500) {
          yield withVariables(
              retryable(UNAVAILABLE, "ScaleDown service error" + detail, null, retryAfter), vars);
        }
        yield fatal(INVALID_REQUEST, "Unexpected ScaleDown response" + detail, null, vars);
      }
    };
  }

  static String truncate(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= MAX_BODY_CHARS ? body : body.substring(0, MAX_BODY_CHARS) + "...";
  }

  private static <T extends ConnectorException> T withVariables(
      T exception, Map<String, Object> variables) {
    if (variables != null && !variables.isEmpty()) {
      // ConnectorRetryException has no constructor taking variables, so set the protected field.
      try {
        var field = ConnectorException.class.getDeclaredField("errorVariables");
        field.setAccessible(true);
        field.set(exception, Map.copyOf(variables));
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // Variables are a convenience; the message still carries the details.
      }
    }
    return exception;
  }
}
