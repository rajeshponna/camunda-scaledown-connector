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

/** POST /classify: scores text, a document or a batch of chunks against user-defined labels. */
public class ClassifyOperation implements ScaleDownOperation {

  static final int MAX_LABELS_SINGLE = 20;
  static final int MAX_LABELS_MULTI = 16;
  static final int MAX_CHUNKS = 50;

  @Override
  public Result execute(ScaleDownRequest request, ScaleDownClient client, DocumentEncoder encoder) {
    ObjectMapper mapper = client.mapper();
    ScaleDownRequest.ClassifyOptions opts =
        request.classify() != null
            ? request.classify()
            : new ScaleDownRequest.ClassifyOptions(null, null, null, null);
    boolean multi = Boolean.TRUE.equals(opts.multiLabel());
    List<Map<String, Object>> labels = normalizeLabels(mapper, opts.labels(), multi);

    ResolvedInput input = resolveInput(request, encoder, true);
    Map<String, Object> body = new LinkedHashMap<>();
    switch (input.source()) {
      case "document" -> {
        body.put("document", input.document().base64());
        body.put("document_mime_type", input.document().mimeType());
      }
      case "chunks" -> body.put("chunks", normalizeChunks(mapper, input.chunks()));
      default -> body.put("text", input.text());
    }
    body.put("labels", labels);
    body.put("multi_label", multi);
    if (multi && opts.threshold() != null) {
      body.put("threshold", opts.threshold());
    }
    if (!multi) {
      body.put("reasoning", !Boolean.FALSE.equals(opts.reasoning()));
    }

    JsonNode res = client.post("/classify", body);
    return toResult(request, mapper, res, multi, "chunks".equals(input.source()));
  }

  private Result toResult(
      ScaleDownRequest request, ObjectMapper mapper, JsonNode res, boolean multi, boolean batch) {
    Double min = request.reviewOrEmpty().minConfidence();
    Map<String, Object> out = new LinkedHashMap<>();
    List<String> low = new ArrayList<>();

    String topLabel = textOrNull(res.path("top_label"));
    Map<String, Double> scores = scores(res.path("scores"));
    Double confidence = topLabel != null ? scores.get(topLabel) : null;

    out.put("topLabel", topLabel);
    out.put("confidence", confidence);
    out.put("scores", scores);
    putIfNotNull(out, "reasoning", textOrNull(res.path("reasoning")));

    JsonNode results = res.path("results");
    if (batch) {
      List<Map<String, Object>> items = new ArrayList<>();
      for (JsonNode r : results) {
        Map<String, Object> item = new LinkedHashMap<>();
        String id = textOrNull(r.path("id"));
        String itemTop = textOrNull(r.path("top_label"));
        Map<String, Double> itemScores = scores(r.path("scores"));
        Double itemConf = itemTop != null ? itemScores.get(itemTop) : null;
        item.put("id", id);
        item.put("topLabel", itemTop);
        item.put("confidence", itemConf);
        item.put("matchedLabels", stringList(r.path("matched_labels")));
        item.put("scores", itemScores);
        items.add(item);
        boolean itemLow = multi ? isEmpty(r.path("matched_labels")) && min != null : below(itemConf, min);
        if (itemLow) {
          low.add(id != null ? id : String.valueOf(items.size()));
        }
      }
      out.put("results", items);
      out.put("chunkCount", intOrNull(res.path("chunk_count")));
    } else {
      List<String> matched = multi ? stringList(results.path(0).path("matched_labels")) : List.of();
      if (multi) {
        out.put("matchedLabels", matched);
        if (min != null && matched.isEmpty()) {
          low.add("matchedLabels");
        }
      } else if (below(confidence, min)) {
        low.add(topLabel);
      }
    }

    addOcrAndRaw(out, request, mapper, res, res);
    return new Result(out, low);
  }

  static List<Map<String, Object>> normalizeLabels(ObjectMapper mapper, Object raw, boolean multi) {
    Object value = parseJsonish(mapper, raw, "Labels");
    List<Map<String, Object>> labels = new ArrayList<>();
    if (value instanceof Map<?, ?> map) {
      map.forEach((k, v) -> labels.add(label(String.valueOf(k), v == null ? null : String.valueOf(v))));
    } else if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> m) {
          Object name = m.get("name");
          Object rubric = m.get("rubric");
          labels.add(label(name == null ? null : String.valueOf(name), rubric == null ? null : String.valueOf(rubric)));
        } else {
          throw invalid("Each label needs a rubric. Use ={label: \"yes/no question\"} or a list of {name, rubric}.");
        }
      }
    } else {
      throw invalid("Labels are required: a context {label: rubric} or a list of {name, rubric}.");
    }
    if (labels.isEmpty()) {
      throw invalid("At least one label is required.");
    }
    int max = multi ? MAX_LABELS_MULTI : MAX_LABELS_SINGLE;
    if (labels.size() > max) {
      throw invalid("ScaleDown accepts at most " + max + " labels in " + (multi ? "multi" : "single")
          + "-label mode; got " + labels.size() + ".");
    }
    return labels;
  }

  private static Map<String, Object> label(String name, String rubric) {
    if (isBlank(name)) {
      throw invalid("Every label needs a name.");
    }
    if (isBlank(rubric)) {
      throw invalid("Label '" + name + "' needs a rubric (a yes/no question).");
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", name);
    m.put("rubric", rubric);
    return m;
  }

  static List<Map<String, Object>> normalizeChunks(ObjectMapper mapper, Object raw) {
    Object value = parseJsonish(mapper, raw, "Chunks");
    if (!(value instanceof List<?> list) || list.isEmpty()) {
      throw invalid("Chunks must be a non-empty list of strings or {id, text} contexts.");
    }
    if (list.size() > MAX_CHUNKS) {
      throw invalid("ScaleDown accepts at most " + MAX_CHUNKS + " chunks per request; got " + list.size()
          + ". Use a multi-instance task to split larger batches.");
    }
    List<Map<String, Object>> chunks = new ArrayList<>();
    int i = 0;
    for (Object item : list) {
      i++;
      Map<String, Object> chunk = new LinkedHashMap<>();
      if (item instanceof Map<?, ?> m) {
        Object id = m.get("id");
        Object text = m.get("text");
        if (text == null || String.valueOf(text).isBlank()) {
          throw invalid("Chunk " + i + " has no text.");
        }
        chunk.put("id", id == null ? String.valueOf(i) : String.valueOf(id));
        chunk.put("text", String.valueOf(text));
      } else if (item != null && !String.valueOf(item).isBlank()) {
        chunk.put("id", String.valueOf(i));
        chunk.put("text", String.valueOf(item));
      } else {
        throw invalid("Chunk " + i + " is empty.");
      }
      chunks.add(chunk);
    }
    return chunks;
  }

  private static Map<String, Double> scores(JsonNode node) {
    Map<String, Double> scores = new LinkedHashMap<>();
    if (node != null && node.isObject()) {
      node.properties().forEach(e -> scores.put(e.getKey(), e.getValue().asDouble()));
    }
    return scores;
  }

  private static List<String> stringList(JsonNode node) {
    List<String> list = new ArrayList<>();
    if (node != null && node.isArray()) {
      node.forEach(n -> list.add(n.isTextual() ? n.asText() : n.path("name").asText(n.asText())));
    }
    return list;
  }

  private static boolean isEmpty(JsonNode node) {
    return node == null || !node.isArray() || node.isEmpty();
  }
}
