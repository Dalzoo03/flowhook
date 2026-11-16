package dev.flowhook.events;

import java.util.Map;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "EventInput", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record EventRequest(
        @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 128) String eventId,
        @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 128) String type,
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, Object> data) {
}
