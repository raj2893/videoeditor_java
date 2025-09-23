package com.example.videoeditor.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.Range;

@Data
public class VideoSpeedRequest {
    @NotNull(message = "Speed cannot be null")
    private Double speed;
}