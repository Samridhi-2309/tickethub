package com.tickethub.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmRequest(
        @NotBlank String holdId
) {}
