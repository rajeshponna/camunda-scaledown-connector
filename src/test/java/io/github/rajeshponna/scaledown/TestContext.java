package io.github.rajeshponna.scaledown;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.connector.api.document.Document;
import io.camunda.connector.api.document.DocumentCreationRequest;
import io.camunda.connector.api.document.DocumentReference;
import io.camunda.connector.api.outbound.JobContext;
import io.camunda.connector.api.outbound.OutboundConnectorContext;

/** Binds a JSON string the way the runtime binds job variables (minus documents and secrets). */
record TestContext(ObjectMapper mapper, String variables) implements OutboundConnectorContext {

  @Override
  public JobContext getJobContext() {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T bindVariables(Class<T> cls) {
    try {
      return mapper.readValue(variables, cls);
    } catch (Exception e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  }

  @Override
  public Document resolve(DocumentReference reference) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Document create(DocumentCreationRequest request) {
    throw new UnsupportedOperationException();
  }
}
