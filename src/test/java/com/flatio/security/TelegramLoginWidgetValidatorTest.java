package com.flatio.security;

import com.flatio.common.exception.InvalidTelegramAuthException;
import com.flatio.telegram.config.BotConfig;
import com.flatio.web.dto.TelegramLoginWidgetRequest;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramLoginWidgetValidatorTest {

  private static final String BOT_TOKEN = "000000:TEST_TOKEN_FOR_UNIT_TESTS";

  @Mock
  private BotConfig botConfig;

  private TelegramLoginWidgetValidator validator;

  @BeforeEach
  void setUp() {
    validator = new TelegramLoginWidgetValidator(botConfig);
  }

  @Test
  void should_not_throw_when_signature_is_valid_with_all_fields_present() {
    // Given
    when(botConfig.token()).thenReturn(BOT_TOKEN);
    long authDate = Instant.now().getEpochSecond();
    var request = signedRequest(12345L, "John", "Doe", "johndoe",
        "https://t.me/i/userpic/320/john.jpg", authDate, BOT_TOKEN);

    // When / Then
    assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
  }

  @Test
  void should_not_throw_when_optional_fields_are_absent() {
    // Given — Telegram omits last_name/username/photo_url when the user has not set them
    when(botConfig.token()).thenReturn(BOT_TOKEN);
    long authDate = Instant.now().getEpochSecond();
    var request = signedRequest(12345L, "John", null, null, null, authDate, BOT_TOKEN);

    // When / Then
    assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
  }

  @Test
  void should_throw_when_signature_does_not_match() {
    // Given
    when(botConfig.token()).thenReturn(BOT_TOKEN);
    long authDate = Instant.now().getEpochSecond();
    var signed = signedRequest(12345L, "John", null, null, null, authDate, BOT_TOKEN);
    var tampered = new TelegramLoginWidgetRequest(
        signed.id(), signed.firstName(), signed.lastName(), signed.username(),
        signed.photoUrl(), signed.authDate(), signed.hash() + "tampered");

    // When / Then
    assertThatThrownBy(() -> validator.validate(tampered))
        .isInstanceOf(InvalidTelegramAuthException.class)
        .hasMessageContaining("signature");
  }

  @Test
  void should_throw_when_signed_with_different_bot_token() {
    // Given
    when(botConfig.token()).thenReturn(BOT_TOKEN);
    long authDate = Instant.now().getEpochSecond();
    var request = signedRequest(12345L, "John", null, null, null, authDate, "999999:ANOTHER_TOKEN");

    // When / Then
    assertThatThrownBy(() -> validator.validate(request))
        .isInstanceOf(InvalidTelegramAuthException.class)
        .hasMessageContaining("signature");
  }

  @Test
  void should_throw_when_optional_field_present_at_signing_is_omitted_at_validation() {
    // Given — signed including username, but the field is stripped before validation;
    // proves optional fields are actually part of the signed data, not ignored
    when(botConfig.token()).thenReturn(BOT_TOKEN);
    long authDate = Instant.now().getEpochSecond();
    var signed = signedRequest(12345L, "John", null, "johndoe", null, authDate, BOT_TOKEN);
    var stripped = new TelegramLoginWidgetRequest(
        signed.id(), signed.firstName(), signed.lastName(), null,
        signed.photoUrl(), signed.authDate(), signed.hash());

    // When / Then
    assertThatThrownBy(() -> validator.validate(stripped))
        .isInstanceOf(InvalidTelegramAuthException.class)
        .hasMessageContaining("signature");
  }

  @Test
  void should_throw_when_auth_date_is_too_old() {
    // Given — auth_date 25 hours in the past (Telegram's recommended max age is 24h)
    when(botConfig.token()).thenReturn(BOT_TOKEN);
    long authDate = Instant.now().getEpochSecond() - 25 * 3600;
    var request = signedRequest(12345L, "John", null, null, null, authDate, BOT_TOKEN);

    // When / Then
    assertThatThrownBy(() -> validator.validate(request))
        .isInstanceOf(InvalidTelegramAuthException.class)
        .hasMessageContaining("expired");
  }

  // -------------------------------------------------------------------------
  // Test fixture: independent reimplementation of the Login Widget's signing
  // algorithm, used only to produce correctly-signed payloads for the tests above.
  // -------------------------------------------------------------------------

  private TelegramLoginWidgetRequest signedRequest(
      Long id, String firstName, String lastName, String username, String photoUrl,
      long authDate, String botToken
  ) {
    var fields = new TreeMap<String, String>();
    fields.put("id", String.valueOf(id));
    fields.put("first_name", firstName);
    if (lastName != null) {
      fields.put("last_name", lastName);
    }
    if (username != null) {
      fields.put("username", username);
    }
    if (photoUrl != null) {
      fields.put("photo_url", photoUrl);
    }
    fields.put("auth_date", String.valueOf(authDate));

    String dataCheckString = fields.entrySet().stream()
        .map(e -> e.getKey() + "=" + e.getValue())
        .collect(Collectors.joining("\n"));
    byte[] secretKey = sha256(botToken.getBytes(StandardCharsets.UTF_8));
    byte[] hashBytes = hmacSha256(secretKey, dataCheckString.getBytes(StandardCharsets.UTF_8));
    String hash = HexFormat.of().formatHex(hashBytes);

    return new TelegramLoginWidgetRequest(id, firstName, lastName, username, photoUrl, authDate, hash);
  }

  private byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
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
}
