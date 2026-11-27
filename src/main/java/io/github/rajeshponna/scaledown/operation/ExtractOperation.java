package io.github.rajeshponna.scaledown.operation;

import static io.github.rajeshponna.scaledown.operation.OperationSupport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rajeshponna.scaledown.client.ScaleDownClient;
import io.github.rajeshponna.scaledown.document.DocumentEncoder;
import io.github.rajeshponna.scaledown.model.ScaleDownRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POST /v1/chat/completions with model extract: named-entity extraction driven by a JSON schema.
 * The response is flattened into {@code fields} (best value per field) so BPMN gateways and
 * forms can use it without FEEL list gymnastics.
 */
public class ExtractOperation implements ScaleDownOperation {

  static final Set<String> MODELS = Set.of("extract", "extract-1");
  static final String DOCUMENT_PROMPT = "Extract the requested fields from the attached document.";

  @Override
  public Result execute(ScaleDownRequest request, ScaleDownClient client, DocumentEncoder encoder) {
    ObjectMapper mapper = client.mapper();
    ScaleDownRequest.ExtractOptions opts =
            request.extract() != null
                    ? request.extract()
                    : new ScaleDownRequest.ExtractOptions(null, null, null, null, null, null, null, null);

    Map<String, Object> schema = buildSchema(mapper, opts);
    ResolvedInput input = resolveInput(request, encoder, false);

    String model = isBlank(opts.model()) ? "extract-1" : opts.model().trim();
    if (!MODELS.contains(model)) {
      throw invalid("Extract model must be extract or extract-1; got '" + model + "'.");
    }

    List<Map<String, Object>> messages = new ArrayList<>();
    if (!isBlank(opts.instruction())) {
      messages.add(Map.of("role", "system", "content", opts.instruction()));
    }
    // ScaleDown rejects an empty user message, even when a document is attached (HTTP 400:
    // "Domain requests require input text..."). With a document, it reads the file and appends
    // its text after this message, so a short neutral instruction is enough.
    String userText = input.text() != null ? input.text() : DOCUMENT_PROMPT;
    messages.add(Map.of("role", "user", "content", userText));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("messages", messages);
    body.put("response_format", Map.of(
            "type", "json_schema",
            "json_schema", Map.of("name", "extraction", "schema", schema)));
    putIfNotNull(body, "threshold", opts.threshold());
    putIfNotNull(body, "top_n", opts.topN());
    putIfNotNull(body, "context_chars", opts.contextChars());
    if (input.isDocument()) {
      body.put("document", input.document().base64());
      body.put("document_mime_type", input.document().mimeType());
    }

    JsonNode res = client.post("/v1/chat/completions", body);
    JsonNode content = parseChatContent(mapper, res);
    return toResult(request, mapper, res, content, schema);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> buildSchema(ObjectMapper mapper, ScaleDownRequest.ExtractOptions opts) {
    String mode = isBlank(opts.schemaMode()) ? "fields" : opts.schemaMode().trim();
    if (mode.equals("jsonSchema")) {
      Object parsed = parseJsonish(mapper, opts.jsonSchema(), "JSON schema");
      Map<String, Object> schema = asMap(parsed, "JSON schema");
      if (!(schema.get("properties") instanceof Map<?, ?> props) || props.isEmpty()) {
        throw invalid("JSON schema needs a non-empty 'properties' object.");
      }
      String json = schema.toString();
      if (schema.containsKey("$defs") || json.contains("$ref")) {
        throw invalid("ScaleDown does not support $ref or $defs. Inline the nested schemas.");
      }
      Map<String, Object> copy = new LinkedHashMap<>(schema);
      copy.putIfAbsent("type", "object");
      return copy;
    }
    if (!mode.equals("fields")) {
      throw invalid("Schema mode must be fields or jsonSchema; got '" + mode + "'.");
    }
    Object parsed = parseJsonish(mapper, opts.fields(), "Fields");
    if (parsed == null) {
      throw invalid("Fields are required, e.g. ={vendor: \"Company that issued the invoice\"}.");
    }
    Map<String, Object> fields = asMap(parsed, "Fields");
    if (fields.isEmpty()) {
      throw invalid("At least one field is required.");
    }
    Map<String, Object> properties = new LinkedHashMap<>();
    fields.forEach((name, desc) -> {
      Map<String, Object> prop = new LinkedHashMap<>();
      prop.put("type", "string");
      if (desc instanceof Map<?, ?> m) {
        prop.putAll((Map<String, Object>) m); // allow {type, description, enum} per field
      } else if (desc != null) {
        prop.put("description", String.valueOf(desc));
      }
      properties.put(name, prop);
    });
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    return schema;
  }

  @SuppressWarnings("unchecked")
  private Result toResult(
          ScaleDownRequest request,
          ObjectMapper mapper,
          JsonNode res,
          JsonNode content,
          Map<String, Object> schema) {
    Double min = request.reviewOrEmpty().minConfidence();

    Map<String, Object> fields = new LinkedHashMap<>();
    Map<String, List<String>> values = new LinkedHashMap<>();
    Map<String, Double> confidences = new LinkedHashMap<>();
    List<Map<String, Object>> entities = new ArrayList<>();

    for (JsonNode e : content.path("entities")) {
      String type = textOrNull(e.path("type"));
      String text = textOrNull(e.path("text"));
      Double conf = doubleOrNull(e.path("confidence"));
      if (type == null) {
        continue;
      }
      Map<String, Object> entity = new LinkedHashMap<>();
      entity.put("type", type);
      entity.put("text", text);
      entity.put("confidence", conf);
      putIfNotNull(entity, "start", intOrNull(e.path("start")));
      putIfNotNull(entity, "end", intOrNull(e.path("end")));
      putIfNotNull(entity, "context", textOrNull(e.path("context")));
      entities.add(entity);

      values.computeIfAbsent(type, k -> new ArrayList<>()).add(text);
      Double best = confidences.get(type);
      if (!confidences.containsKey(type) || (conf != null && (best == null || conf > best))) {
        confidences.put(type, conf);
        fields.put(type, text);
      }
    }

    Object structured = toPlain(mapper, content.path("structured_result"));
    // Nested/array results live in structured_result; surface them as fields too.
    if (structured instanceof Map<?, ?> s) {
      ((Map<String, Object>) s).forEach(fields::putIfAbsent);
    }

    List<String> missing = new ArrayList<>();
    Object props = schema.get("properties");
    if (props instanceof Map<?, ?> p) {
      for (Object name : p.keySet()) {
        if (!fields.containsKey(String.valueOf(name))) {
          missing.add(String.valueOf(name));
        }
      }
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("fields", fields);
    out.put("confidences", confidences);
    out.put("values", values);
    out.put("missingFields", missing);
    out.put("structuredResult", structured);
    out.put("entities", entities);
    putIfNotNull(out, "inputTokens", intOrNull(content.path("input_tokens")));
    putIfNotNull(out, "usage", usage(mapper, res));
    addOcrAndRaw(out, request, mapper, content, res);
    return new Result(out, lowConfidence(confidences, min));
  }
}