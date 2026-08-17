package com.flatio.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Payload delivered by the Telegram Login Widget's {@code onauth} callback, forwarded to the
 * backend verbatim as JSON.
 *
 * <p>{@code lastName}, {@code username} and {@code photoUrl} are optional because Telegram omits
 * them from the callback when the user has not set them — absent fields must be excluded from
 * the signature check string, not signed as empty strings.
 */
@Schema(description = "Telegram Login Widget authentication callback payload")
public record TelegramLoginWidgetRequest(
    @Schema(description = "Telegram user ID", example = "123456789", requiredMode = REQUIRED)
    @NotNull Long id,

    @Schema(description = "Telegram first name", example = "John", requiredMode = REQUIRED)
    @NotBlank @JsonProperty("first_name") String firstName,

    @Schema(description = "Telegram last name, if set", example = "Doe")
    @JsonProperty("last_name") String lastName,

    @Schema(description = "Telegram username handle, if set", example = "johndoe")
    String username,

    @Schema(description = "Telegram profile photo URL, if set", example = "https://t.me/i/userpic/320/john.jpg")
    @JsonProperty("photo_url") String photoUrl,

    @Schema(description = "Unix timestamp of the widget authentication", example = "1700000000", requiredMode = REQUIRED)
    @NotNull @JsonProperty("auth_date") Long authDate,

    @Schema(description = "HMAC-SHA256 signature over the other fields", example = "abc123...", requiredMode = REQUIRED)
    @NotBlank String hash
) {}
