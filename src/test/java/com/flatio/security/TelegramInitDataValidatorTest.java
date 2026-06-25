package com.flatio.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.common.exception.InvalidTelegramAuthException;
import com.flatio.telegram.config.BotConfig;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramInitDataValidatorTest {

  private static final String BOT_TOKEN = "000000:TEST_TOKEN_FOR_UNIT_TESTS";

  @Mock
  private BotConfig botConfig;

  private TelegramInitDataValidator validator;

  @BeforeEach
  void setUp() {
    validator = new TelegramInitDataValidator(botConfig, new ObjectMapper());
  }

  @Test
  void should_extract_user_when_signature_is_valid() {
    // Given
    when(botConfig.token()).thenReturn(BOT_TOKEN);
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("user", "{\"id\":12345,\"username\":\"john\",\"first_name\":\"John\"}");
    fields.put("auth_date", String.valueOf(Instant.now().getEpochSecond()));
    String initData = signedInitData(fields);

    // When
    var result = validator.validate(initData);

    // Then
    assertThat(result.id()).isEqualTo(12345L);
    assertThat(result.username()).isEqualTo("john");
    assertThat(result.firstName()).isEqualTo("John");
  }

  @Test
  void should_throw_when_hash_field_missing() {
    // Given — hash is checked before botConfig.token() is ever consulted
    String initData = "auth_date=" + Instant.now().getEpochSecond() + "&user=%7B%22id%22%3A1%7D";

    // When / Then
    assertThatThrownBy(() -> validator.validate(initData))
        .isInstanceOf(InvalidTelegramAuthException.class)
        .hasMessageContaining("hash");
  }

  @Test
  void should_throw_when_signature_does_not_match() {
    // Given
    when(botConfig.token()).thenReturn(BOT_TOKEN);
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("user", "{\"id\":12345}");
    fields.put("auth_date", String.valueOf(Instant.now().getEpochSecond()));
    String initData = signedInitData(fields) + "tampered";

    // When / Then
    assertThatThrownBy(() -> validator.validate(initData))
        .isInstanceOf(InvalidTelegramAuthException.class)
        .hasMessageContaining("signature");
  }

  @Test
  void should_throw_when_signed_with_different_bot_token() {
    // Given — initData signed with a token the validator does not expect
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("user", "{\"id\":12345}");
    fields.put("auth_date", String.valueOf(Instant.now().getEpochSecond()));
    String initData = signWithToken(fields, "999999:ANOTHER_TOKEN");
    when(botConfig.token()).thenReturn(BOT_TOKEN);

    // When / Then
    assertThatThrownBy(() -> validator.validate(initData))
        .isInstanceOf(InvalidTelegramAuthException.class)
        .hasMessageContaining("signature");
  }

  @Test
  void should_throw_when_auth_date_is_too_old() {
    // Given — auth_date 25 hours in the past (Telegram's recommended max age is 24h)
    when(botConfig.token()).thenReturn(BOT_TOKEN);
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("user", "{\"id\":12345}");
    fields.put("auth_date", String.valueOf(Instant.now().getEpochSecond() - 25 * 3600));
    String initData = signedInitData(fields);

    // When / Then
    assertThatThrownBy(() -> validator.validate(initData))
        .isInstanceOf(InvalidTelegramAuthException.class)
        .hasMessageContaining("expired");
  }

  @Test
  void should_throw_when_user_field_missing() {
    // Given
    when(botConfig.token()).thenReturn(BOT_TOKEN);
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("auth_date", String.valueOf(Instant.now().getEpochSecond()));
    String initData = signedInitData(fields);

    // When / Then
    assertThatThrownBy(() -> validator.validate(initData))
        .isInstanceOf(InvalidTelegramAuthException.class)
        .hasMessageContaining("user");
  }

  @Test
  void should_throw_when_user_field_is_not_valid_json() {
    // Given
    when(botConfig.token()).thenReturn(BOT_TOKEN);
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("user", "not-json");
    fields.put("auth_date", String.valueOf(Instant.now().getEpochSecond()));
    String initData = signedInitData(fields);

    // When / Then
    assertThatThrownBy(() -> validator.validate(initData))
        .isInstanceOf(InvalidTelegramAuthException.class)
        .hasMessageContaining("user");
  }

  // -------------------------------------------------------------------------
  // Test fixture: independent reimplementation of Telegram's signing algorithm,
  // used only to produce correctly-signed initData strings for the tests above.
  // -------------------------------------------------------------------------

  private String signedInitData(Map<String, String> fields) {
    return signWithToken(fields, BOT_TOKEN);
  }

  private String signWithToken(Map<String, String> fields, String botToken) {
    String dataCheckString = String.join("\n",
        new TreeMap<>(fields).entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .toArray(String[]::new));
    byte[] secretKey = hmacSha256("WebAppData".getBytes(StandardCharsets.UTF_8), botToken.getBytes(StandardCharsets.UTF_8));
    byte[] hash = hmacSha256(secretKey, dataCheckString.getBytes(StandardCharsets.UTF_8));
    String hashHex = HexFormat.of().formatHex(hash);

    StringBuilder sb = new StringBuilder();
    fields.forEach((k, v) -> sb.append(k).append('=').append(urlEncode(v)).append('&'));
    sb.append("hash=").append(hashHex);
    return sb.toString();
  }

  private byte[] hmacSha256(byte[] key, byte[] message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(message);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(e);
    }
  }

  private String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
