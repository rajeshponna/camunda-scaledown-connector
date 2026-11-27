package io.github.rajeshponna.scaledown.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;

/** Thin HTTP client for the ScaleDown API. One instance per job execution. */
public class ScaleDownClient {

  public static final String DEFAULT_BASE_URL = "https://api.scaledown.xyz";
  static final String USER_AGENT = "camunda-scaledown-connector/1.0.0";

  private final HttpClient httpClient;
  private final ObjectMapper mapper;
  private final String baseUrl;
  private final String apiKey;
  private final Duration timeout;

  public ScaleDownClient(
      HttpClient httpClient, ObjectMapper mapper, String baseUrl, String apiKey, Duration timeout) {
    this.httpClient = httpClient;
    this.mapper = mapper;
    this.baseUrl = normalizeBaseUrl(baseUrl);
    this.apiKey = apiKey;
    this.timeout = timeout;
  }

  public ObjectMapper mapper() {
    return mapper;
  }

  /** POSTs a JSON body and returns the parsed response, or throws a mapped connector error. */
  public JsonNode post(String path, Object body) {
    String json;
    try {
      json = mapper.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      throw ScaleDownErrors.fatal(
          ScaleDownErrors.INVALID_INPUT, "Could not serialize request: " + e.getOriginalMessage());
    }

    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("x-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (HttpTimeoutException e) {
      throw ScaleDownErrors.retryable(
          ScaleDownErrors.TIMEOUT,
          "ScaleDown did not respond within " + timeout.toSeconds() + " seconds",
          e,
          null);
    } catch (IOException e) {
      throw ScaleDownErrors.retryable(
          ScaleDownErrors.UNAVAILABLE, "Could not reach ScaleDown: " + e.getMessage(), e, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ScaleDownErrors.retryable(
          ScaleDownErrors.UNAVAILABLE, "Interrupted while calling ScaleDown", e, null);
    }

    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      throw ScaleDownErrors.forHttpStatus(
          status, response.body(), parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null)));
    }

    try {
      JsonNode node = mapper.readTree(response.body());
      if (node == null || node.isMissingNode() || node.isNull()) {
        throw new IOException("empty body");
      }
      return node;
    } catch (IOException e) {
      throw ScaleDownErrors.retryable(
          ScaleDownErrors.INVALID_RESPONSE,
          "ScaleDown returned a response that is not valid JSON: "
              + ScaleDownErrors.truncate(response.body()),
          e,
          null);
    }
  }

  static Duration parseRetryAfter(String header) {
    if (header == null || header.isBlank()) {
      return null;
    }
    try {
      long seconds = Long.parseLong(header.trim());
      return seconds > 0 ? Duration.ofSeconds(Math.min(seconds, 3600)) : null;
    } catch (NumberFormatException e) {
      return null; // HTTP-date form: fall back to the template's retry backoff
    }
  }

  /**
   * The API key travels in a header, so plain HTTP is only allowed for local development hosts.
   */
  static String normalizeBaseUrl(String raw) {
    String url = (raw == null || raw.isBlank()) ? DEFAULT_BASE_URL : raw.trim();
    while (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException e) {
      throw ScaleDownErrors.fatal(ScaleDownErrors.INVALID_INPUT, "Base URL is not a valid URL: " + url);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")
        || host.equals("[::1]") || host.equals("host.docker.internal");
    if (!scheme.equals("https") && !(scheme.equals("http") && local)) {
      throw ScaleDownErrors.fatal(
          ScaleDownErrors.INVALID_INPUT,
          "Base URL must use https (plain http is only allowed for localhost): " + url);
    }
    return url;
  }
}
