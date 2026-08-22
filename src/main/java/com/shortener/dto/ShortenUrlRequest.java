package com.shortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ShortenUrlRequest(
        @NotBlank(message = "URL cannot be blank")
        @Size(min = 10, max = 2048, message = "URL must contain between 10 and 2048 characters")
        String originalUrl,

        @Pattern(regexp = "^[a-zA-Z0-9]{3,10}$",
                message = "Custom code must contain 3-10 alphanumeric characters")
        String customCode
) {
}
