package io.github.rajeshponna.scaledown.operation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rajeshponna.scaledown.client.ScaleDownErrors;
import io.github.rajeshponna.scaledown.document.DocumentEncoder;
import io.github.rajeshponna.scaledown.document.DocumentEncoder.EncodedDocument;
import io.github.rajeshponna.scaledown.model.ScaleDownRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared helpers for building requests and reading responses. */
final class OperationSupport {

  private static final TypeReference<Object> ANY = new TypeReference<>() {};

  private OperationSupport() {}

  /** What the user selected as input for a document-capable operation. */
  record ResolvedInput(String source, String text, EncodedDocument document, Object chunks) {
    boolean isDocument() {
      return document != null;
    }
  }

  static ResolvedInput resolveInput(
      ScaleDownRequest request, DocumentEncoder encoder, boolean allowChunks) {
    ScaleDownRequest.Input input = request.inputOrEmpty();
    String source = input.source() == null ? "text" : input.source().trim().toLowerCase(Locale.ROOT);
    switch (source) {
      case "text" -> {
        if (isBlank(input.text())) {
          throw invalid("Input source is 'text' but the text is empty.");
        }
        return new ResolvedInput(source, input.text(), null, null);
      }
      case "document" -> {
        EncodedDocument doc = encoder.encode(input.document(), input.mimeType());
        return new ResolvedInput(source, blankToNull(input.text()), doc, null);
      }
      case "chunks" -> {
        if (!allowChunks) {
          throw invalid("Batch chunks are only supported by the Classify operation.");
        }
        return new ResolvedInput(source, null, null, input.chunks());
      }
      default -> throw invalid("Unknown input source '" + input.source() + "'. Use text, document or chunks.");
    }
  }

  /**
   * FEEL contexts arrive as maps, but users sometimes paste JSON into a plain string. Accept both.
   */
  static Object parseJsonish(ObjectMapper mapper, Object value, String fieldLabel) {
    if (value instanceof String s) {
      String trimmed = s.trim();
      if (trimmed.isEmpty()) {
        return null;
      }
      if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        try {
          return mapper.readValue(trimmed, ANY);
        } catch (JsonProcessingException e) {
          throw invalid(fieldLabel + " is not valid JSON: " + e.getOriginalMessage());
        }
      }
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> asMap(Object value, String fieldLabel) {
    if (value instanceof Map<?, ?> m) {
      return (Map<String, Object>) m;
    }
    throw invalid(fieldLabel + " must be a FEEL context (key: value pairs), e.g. ={a: \"...\"}.");
  }

  static Object toPlain(ObjectMapper mapper, JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    return mapper.convertValue(node, ANY);
  }

  /** chat.completions puts the model output in choices[0].message.content as a JSON string. */
  static JsonNode parseChatContent(ObjectMapper mapper, JsonNode response) {
    JsonNode content = response.path("choices").path(0).path("message").path("content");
    if (content.isMissingNode() || content.isNull()) {
      throw ScaleDownErrors.retryable(
          ScaleDownErrors.INVALID_RESPONSE,
          "ScaleDown response has no choices[0].message.content",
          null,
          null);
    }
    if (content.isObject()) {
      return content;
    }
    String text = content.asText();
    try {
      JsonNode parsed = mapper.readTree(text);
      if (parsed != null && parsed.isObject()) {
        return parsed;
      }
    } catch (JsonProcessingException ignored) {
      // fall through: plain text content
    }
    return mapper.createObjectNode().put("_text", text);
  }

  static Double doubleOrNull(JsonNode node) {
    return node != null && node.isNumber() ? node.asDouble() : null;
  }

  static Integer intOrNull(JsonNode node) {
    return node != null && node.isNumber() ? node.asInt() : null;
  }

  static String textOrNull(JsonNode node) {
    return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
  }

  static void putIfNotNull(Map<String, Object> map, String key, Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  static void addOcrAndRaw(
      Map<String, Object> out,
      ScaleDownRequest request,
      ObjectMapper mapper,
      JsonNode ocrSource,
      JsonNode raw) {
    if (request.includeOcrText()) {
      out.put("ocrText", textOrNull(ocrSource.path("ocr_text")));
    }
    if (request.includeRawResponse()) {
      out.put("raw", toPlain(mapper, raw));
    }
  }

  static boolean below(Double confidence, Double min) {
    return min != null && confidence != null && confidence < min;
  }

  static List<String> lowConfidence(Map<String, Double> confidences, Double min) {
    List<String> low = new ArrayList<>();
    if (min == null) {
      return low;
    }
    confidences.forEach((name, c) -> {
      if (c == null || c < min) {
        low.add(name);
      }
    });
    return low;
  }

  static Map<String, Object> usage(ObjectMapper mapper, JsonNode response) {
    JsonNode usage = response.path("usage");
    if (usage.isMissingNode() || usage.isNull()) {
      return null;
    }
    Object plain = toPlain(mapper, usage);
    if (plain instanceof Map<?, ?> m) {
      @SuppressWarnings("unchecked")
      Map<String, Object> cast = (Map<String, Object>) m;
      return new LinkedHashMap<>(cast);
    }
    return null;
  }

  static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  static String blankToNull(String s) {
    return isBlank(s) ? null : s;
  }

  static RuntimeException invalid(String message) {
    return ScaleDownErrors.fatal(ScaleDownErrors.INVALID_INPUT, message);
  }
}
