package io.github.rajeshponna.scaledown.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.camunda.connector.api.document.Document;

/**
 * Variables bound from the element template. Each operation reads only its own section, so the
 * template can show and hide groups without the sections interfering with each other.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScaleDownRequest(
    Operation operation,
    Authentication authentication,
    Input input,
    ClassifyOptions classify,
    DecisionsOptions decisions,
    ExtractOptions extract,
    SummarizeOptions summarize,
    CompressOptions compress,
    ReviewOptions review,
    ConnectionOptions connection) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Authentication(String apiKey) {}

  /** source: text | document | chunks. Chunks are a list of strings or {id, text} objects. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Input(
      String source, String text, Document document, String mimeType, Object chunks) {}

  /** labels: context {name: rubric} or list of {name, rubric}. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ClassifyOptions(
      Object labels, Boolean multiLabel, Double threshold, Boolean reasoning) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DecisionsOptions(String model, Object questions) {}

  /** schemaMode: fields | jsonSchema. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ExtractOptions(
      String model,
      String schemaMode,
      Object fields,
      Object jsonSchema,
      String instruction,
      Double threshold,
      Integer topN,
      Integer contextChars) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SummarizeOptions(String model, String instruction, Integer maxTokens) {}

  /** rate: "auto" or a number between 0 and 1. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CompressOptions(String context, String prompt, Object rate) {}

  /** onLowConfidence: flag | error. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReviewOptions(Double minConfidence, String onLowConfidence) {
    public boolean failOnLowConfidence() {
      return "error".equalsIgnoreCase(onLowConfidence);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ConnectionOptions(
      String baseUrl,
      Integer timeoutSeconds,
      Boolean includeOcrText,
      Boolean includeRawResponse) {}

  // Null-safe accessors so operations never deal with missing sections.

  public Input inputOrEmpty() {
    return input != null ? input : new Input(null, null, null, null, null);
  }

  public ReviewOptions reviewOrEmpty() {
    return review != null ? review : new ReviewOptions(null, null);
  }

  public ConnectionOptions connectionOrEmpty() {
    return connection != null ? connection : new ConnectionOptions(null, null, null, null);
  }

  public boolean includeOcrText() {
    return Boolean.TRUE.equals(connectionOrEmpty().includeOcrText());
  }

  public boolean includeRawResponse() {
    return Boolean.TRUE.equals(connectionOrEmpty().includeRawResponse());
  }
}
