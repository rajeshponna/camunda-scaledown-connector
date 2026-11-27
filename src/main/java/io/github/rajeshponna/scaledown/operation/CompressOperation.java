package io.github.rajeshponna.scaledown.operation;

import static io.github.rajeshponna.scaledown.operation.OperationSupport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rajeshponna.scaledown.client.ScaleDownClient;
import io.github.rajeshponna.scaledown.client.ScaleDownErrors;
import io.github.rajeshponna.scaledown.document.DocumentEncoder;
import io.github.rajeshponna.scaledown.model.ScaleDownRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** POST /compress/raw/: trims context before it is sent to a downstream LLM. Text only. */
public class CompressOperation implements ScaleDownOperation {

  @Override
  public Result execute(ScaleDownRequest request, ScaleDownClient client, DocumentEncoder encoder) {
    ObjectMapper mapper = client.mapper();
    ScaleDownRequest.CompressOptions opts =
        request.compress() != null ? request.compress() : new ScaleDownRequest.CompressOptions(null, null, null);
    if (isBlank(opts.context())) {
      throw invalid("Compress needs a context: the background text to shrink.");
    }
    if (isBlank(opts.prompt())) {
      throw invalid("Compress needs a prompt: the question you will send to your LLM.");
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("context", opts.context());
    body.put("prompt", opts.prompt());
    body.put("scaledown", Map.of("rate", normalizeRate(opts.rate())));

    JsonNode res = client.post("/compress/raw/", body);

    JsonNode results = res.path("results");
    JsonNode first = results.isArray() ? results.path(0) : results;
    if (first.has("success") && !first.path("success").asBoolean(true)) {
      throw ScaleDownErrors.retryable(
          ScaleDownErrors.INVALID_RESPONSE,
          "ScaleDown could not compress this context",
          null,
          null);
    }

    Integer original = intOrNull(first.path("original_prompt_tokens"));
    Integer compressed = intOrNull(first.path("compressed_prompt_tokens"));
    if (original == null) {
      original = intOrNull(res.path("total_original_tokens"));
    }
    if (compressed == null) {
      compressed = intOrNull(res.path("total_compressed_tokens"));
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("compressedPrompt", textOrNull(first.path("compressed_prompt")));
    out.put("originalPrompt", textOrNull(first.path("original_prompt")));
    out.put("originalTokens", original);
    out.put("compressedTokens", compressed);
    out.put("tokensSaved", original != null && compressed != null ? original - compressed : null);
    putIfNotNull(out, "compressionRatio", doubleOrNull(first.path("compression_ratio")));
    putIfNotNull(out, "latencyMs", intOrNull(res.path("latency_ms")));
    putIfNotNull(out, "inputTokens", intOrNull(res.path("input_tokens")));
    if (request.includeRawResponse()) {
      out.put("raw", toPlain(mapper, res));
    }
    return new Result(out, List.of());
  }

  static Object normalizeRate(Object raw) {
    if (raw == null || (raw instanceof String s && (s.isBlank() || s.trim().equalsIgnoreCase("auto")))) {
      return "auto";
    }
    double rate;
    if (raw instanceof Number n) {
      rate = n.doubleValue();
    } else {
      try {
        rate = Double.parseDouble(String.valueOf(raw).trim());
      } catch (NumberFormatException e) {
        throw invalid("Compression rate must be 'auto' or a number from 0 to 1; got '" + raw + "'.");
      }
    }
    if (rate <= 0 || rate >= 1) {
      throw invalid("Compression rate must be greater than 0 and less than 1 (fraction of tokens to remove); got " + rate + ".");
    }
    return rate;
  }
}
