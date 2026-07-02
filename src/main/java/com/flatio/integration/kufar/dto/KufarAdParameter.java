package com.flatio.integration.kufar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/**
 * Single parameter from a Kufar ad's {@code ad_parameters} array.
 *
 * <p>{@code p} is the machine-readable key (e.g. {@code "rooms"}, {@code "floor"},
 * {@code "re_number_floors"}, {@code "size"}, {@code "region"}, {@code "area"}). {@code vl} is the
 * human-readable value label (a JSON string, a single-element JSON array, or — defensively —
 * a JSON object, all handled by {@link FirstElementDeserializer}). {@code v} is the raw machine
 * value (string or number — normalized to String).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KufarAdParameter(
    String pl,
    String p,
    @JsonDeserialize(using = KufarAdParameter.FirstElementDeserializer.class) String vl,
    @JsonDeserialize(using = KufarAdParameter.FirstElementDeserializer.class) String v
) {

  /**
   * Deserializes a JSON value that may be a String, Number, Array, or Object into a single String.
   *
   * <ul>
   *   <li>JSON String → returned as-is</li>
   *   <li>JSON Number → converted via {@code toString()}</li>
   *   <li>JSON Array → first element converted to String; empty array → {@code null}</li>
   *   <li>JSON Object → skipped, returns {@code null}</li>
   *   <li>JSON null → {@code null}</li>
   * </ul>
   *
   * <p>No known Kufar {@code ad_parameters} value is a JSON object in real API responses
   * (verified against a live response — see issue #311); the object branch is a defensive
   * fallback so an unexpected shape does not throw and skip the whole listing.
   */
  static class FirstElementDeserializer extends StdDeserializer<String> {

    FirstElementDeserializer() {
      super(String.class);
    }

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
      JsonToken token = p.currentToken();
      if (token == JsonToken.VALUE_NULL) {
        return null;
      }
      if (token == JsonToken.START_ARRAY) {
        String first = null;
        while (p.nextToken() != JsonToken.END_ARRAY) {
          if (first == null) {
            first = p.getText();
          }
        }
        return first;
      }
      if (token == JsonToken.START_OBJECT) {
        p.skipChildren();
        return null;
      }
      return p.getText();
    }
  }
}
