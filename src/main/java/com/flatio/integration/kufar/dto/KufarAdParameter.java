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
 * {@code "re_number_floors"}, {@code "size"}, {@code "address"}). {@code vl} is the human-readable
 * value label (may be a JSON string, a single-element JSON array, or a JSON object with address
 * components — all handled transparently by {@link FirstElementDeserializer}).
 * {@code v} is the raw machine value (string or number — normalized to String).
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
   *   <li>JSON Object → address components (city, district, street, building) joined by comma;
   *       {@code null} if no known fields present</li>
   *   <li>JSON null → {@code null}</li>
   * </ul>
   *
   * <p>The object branch exists because the Kufar API may return the {@code address} parameter's
   * {@code vl} as a structured object rather than a plain string. Consuming the full object on
   * {@code START_OBJECT} also keeps the underlying {@link JsonParser} in a consistent state,
   * preventing deserialization failures for the surrounding record.
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
        return assembleAddressFromObject(p);
      }
      return p.getText();
    }

    private static String assembleAddressFromObject(JsonParser p) throws IOException {
      String city = null;
      String district = null;
      String street = null;
      String building = null;
      JsonToken token;
      while ((token = p.nextToken()) != JsonToken.END_OBJECT) {
        if (token != JsonToken.FIELD_NAME) {
          continue;
        }
        String fieldName = p.getText();
        token = p.nextToken();
        if (token.isScalarValue()) {
          String value = p.getText();
          if (value != null && !value.isBlank()) {
            switch (fieldName) {
              case "city" -> city = value.trim();
              case "district" -> district = value.trim();
              case "street" -> street = value.trim();
              case "building", "house", "house_number" -> building = value.trim();
              default -> { }
            }
          }
        } else if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
          p.skipChildren();
        }
      }
      var sb = new StringBuilder();
      appendAddressPart(sb, city);
      appendAddressPart(sb, district);
      appendAddressPart(sb, street);
      appendAddressPart(sb, building);
      return sb.isEmpty() ? null : sb.toString();
    }

    private static void appendAddressPart(StringBuilder sb, String part) {
      if (part != null && !part.isBlank()) {
        if (!sb.isEmpty()) {
          sb.append(", ");
        }
        sb.append(part);
      }
    }
  }
}
