package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateSessionTitleRequest {

    @NotBlank
    private String title;
}
