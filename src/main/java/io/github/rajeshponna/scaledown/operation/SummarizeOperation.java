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

/** POST /v1/chat/completions with model summarize. */
public class SummarizeOperation implements ScaleDownOperation {

  static final Set<String> MODELS = Set.of("summarize", "summarize-1");
  static final int MAX_OUTPUT_TOKENS = 1000;

  @Override
  public Result execute(ScaleDownRequest request, ScaleDownClient client, DocumentEncoder encoder) {
    ObjectMapper mapper = client.mapper();
    ScaleDownRequest.SummarizeOptions opts =
        request.summarize() != null
            ? request.summarize()
            : new ScaleDownRequest.SummarizeOptions(null, null, null);
    ResolvedInput input = resolveInput(request, encoder, false);

    String model = isBlank(opts.model()) ? "summarize-1" : opts.model().trim();
    if (!MODELS.contains(model)) {
      throw invalid("Summarize model must be summarize or summarize-1; got '" + model + "'.");
    }
    Integer maxTokens = opts.maxTokens();
    if (maxTokens != null && (maxTokens < 1 || maxTokens > MAX_OUTPUT_TOKENS)) {
      throw invalid("Max output tokens must be between 1 and " + MAX_OUTPUT_TOKENS + "; got " + maxTokens + ".");
    }

    List<Map<String, Object>> messages = new ArrayList<>();
    if (!isBlank(opts.instruction())) {
      messages.add(Map.of("role", "system", "content", opts.instruction()));
    }
    String userText = input.text() != null ? input.text() : "Summarize the attached document.";
    messages.add(Map.of("role", "user", "content", userText));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("messages", messages);
    putIfNotNull(body, "max_tokens", maxTokens);
    if (input.isDocument()) {
      body.put("document", input.document().base64());
      body.put("document_mime_type", input.document().mimeType());
    }

    JsonNode res = client.post("/v1/chat/completions", body);
    JsonNode content = parseChatContent(mapper, res);

    String summary = content.has("summary") ? textOrNull(content.path("summary")) : textOrNull(content.path("_text"));
    String finishReason = textOrNull(content.path("finish_reason"));
    if (finishReason == null) {
      finishReason = textOrNull(res.path("choices").path(0).path("finish_reason"));
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("summary", summary);
    out.put("truncated", "length".equals(finishReason));
    putIfNotNull(out, "finishReason", finishReason);
    putIfNotNull(out, "notice", textOrNull(content.path("notice")));
    putIfNotNull(out, "inputChars", intOrNull(content.path("input_chars")));
    putIfNotNull(out, "outputChars", intOrNull(content.path("output_chars")));
    putIfNotNull(out, "inputTokens", intOrNull(content.path("input_tokens")));
    putIfNotNull(out, "latencyMs", intOrNull(content.path("latency_ms")));
    putIfNotNull(out, "usage", usage(mapper, res));
    addOcrAndRaw(out, request, mapper, content, res);
    return new Result(out, List.of());
  }
}
