package io.github.rajeshponna.scaledown.operation;

import io.github.rajeshponna.scaledown.client.ScaleDownClient;
import io.github.rajeshponna.scaledown.document.DocumentEncoder;
import io.github.rajeshponna.scaledown.model.ScaleDownRequest;
import java.util.List;
import java.util.Map;

/** One ScaleDown endpoint. Implementations build the request and normalize the response. */
public interface ScaleDownOperation {

  Result execute(ScaleDownRequest request, ScaleDownClient client, DocumentEncoder encoder);

  /**
   * @param response the variables returned to Camunda
   * @param lowConfidence names of items (labels, questions, fields, chunks) below the configured
   *     minimum confidence; empty when no minimum is set
   */
  record Result(Map<String, Object> response, List<String> lowConfidence) {}
}
