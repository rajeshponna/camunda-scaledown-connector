package io.github.rajeshponna.scaledown.operation;

import static io.github.rajeshponna.scaledown.operation.OperationSupport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rajeshponna.scaledown.client.ScaleDownClient;
import io.github.rajeshponna.scaledown.document.DocumentEncoder;
import io.github.rajeshponna.scaledown.model.ScaleDownRequest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** POST /v1/scaledown: Jev-compatible Decisions API with choice, noul and score questions. */
public class DecisionsOperation implements ScaleDownOperation {

  static final Set<String> TYPES = Set.of("choice", "noul", "score");
  static final Set<String> MODELS = Set.of("classify", "classify-1");

  @Override
  public Result execute(ScaleDownRequest request, ScaleDownClient client, DocumentEncoder encoder) {
    ObjectMapper mapper = client.mapper();
    ScaleDownRequest.DecisionsOptions opts =
        request.decisions() != null ? request.decisions() : new ScaleDownRequest.DecisionsOptions(null, null);

    Map<String, Object> questions = validateQuestions(mapper, opts.questions());
    ResolvedInput input = resolveInput(request, encoder, false);

    Map<String, Object> state = new LinkedHashMap<>();
    if (input.text() != null) {
      state.put("text", input.text());
    }
    if (input.isDocument()) {
      state.put("document", input.document().base64());
      state.put("document_mime_type", input.document().mimeType());
    }

    String model = isBlank(opts.model()) ? "classify-1" : opts.model().trim();
    if (!MODELS.contains(model)) {
      throw invalid("Decisions model must be classify or classify-1; got '" + model + "'.");
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("state", state);
    body.put("questions", questions);

    JsonNode res = client.post("/v1/scaledown", body);
    return toResult(request, mapper, res, questions);
  }

  private Result toResult(
      ScaleDownRequest request, ObjectMapper mapper, JsonNode res, Map<String, Object> questions) {
    Double min = request.reviewOrEmpty().minConfidence();
    JsonNode answers = res.path("answers");

    Map<String, Object> decisions = new LinkedHashMap<>();
    Map<String, Double> confidences = new LinkedHashMap<>();
    Map<String, Boolean> flags = new LinkedHashMap<>();

    for (String name : questions.keySet()) {
      JsonNode a = answers.path(name);
      String type = questionType(questions.get(name));
      switch (type) {
        case "choice" -> {
          decisions.put(name, textOrNull(a.path("choice")));
          confidences.put(name, doubleOrNull(a.path("confidence")));
        }
        case "noul" -> {
          Double p = doubleOrNull(a.path("noul"));
          decisions.put(name, p);
          // Confidence of a yes/no answer is how far it is from a coin flip, in either direction.
          confidences.put(name, p == null ? null : Math.max(p, 1 - p));
          flags.put(name, p != null && p >= 0.5);
        }
        case "score" -> {
          decisions.put(name, doubleOrNull(a.path("score")));
          confidences.put(name, doubleOrNull(a.path("confidence")));
        }
        default -> { }
      }
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("decisions", decisions);
    out.put("confidences", confidences);
    out.put("flags", flags);
    out.put("answers", toPlain(mapper, answers));
    putIfNotNull(out, "model", textOrNull(res.path("model")));
    putIfNotNull(out, "usage", usage(mapper, res));
    if (request.includeRawResponse()) {
      out.put("raw", toPlain(mapper, res));
    }
    return new Result(out, lowConfidence(confidences, min));
  }

  static Map<String, Object> validateQuestions(ObjectMapper mapper, Object raw) {
    Object parsed = parseJsonish(mapper, raw, "Questions");
    if (parsed == null) {
      throw invalid("At least one question is required.");
    }
    Map<String, Object> questions = asMap(parsed, "Questions");
    if (questions.isEmpty()) {
      throw invalid("At least one question is required.");
    }
    questions.forEach((name, q) -> {
      if (!(q instanceof Map<?, ?> def)) {
        throw invalid("Question '" + name + "' must be a context with a type, e.g. {type: \"noul\"}.");
      }
      String type = questionType(def);
      if (!TYPES.contains(type)) {
        throw invalid("Question '" + name + "' has type '" + def.get("type") + "'. Use choice, noul or score.");
      }
      Object criteria = def.get("criteria");
      if (type.equals("choice") && (!(criteria instanceof Map<?, ?> c) || c.isEmpty())) {
        throw invalid("Choice question '" + name + "' needs a criteria context {option: description}.");
      }
      if (type.equals("score")) {
        if (!(criteria instanceof java.util.List<?> levels) || levels.size() < 2 || levels.size() > 10) {
          throw invalid("Score question '" + name + "' needs criteria as a list of 2 to 10 levels, lowest first.");
        }
      }
    });
    return questions;
  }

  private static String questionType(Object def) {
    if (def instanceof Map<?, ?> m && m.get("type") != null) {
      return String.valueOf(m.get("type")).trim().toLowerCase(Locale.ROOT);
    }
    return "";
  }
}
