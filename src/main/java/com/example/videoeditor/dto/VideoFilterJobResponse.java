package com.example.videoeditor.dto;

import com.example.videoeditor.entity.VideoFilterJob;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VideoFilterJobResponse {
    private Long id;
    private Long userId;
    private String inputVideoPath;
    private String outputVideoPath;

    private Double brightness;
    private Double contrast;
    private Double saturation;
    private Double temperature;
    private Double gamma;
    private Double shadows;
    private Double highlights;
    private Double vibrance;
    private Double hue;
    private Double exposure;
    private Double tint;
    private Double sharpness;


    private String filterName;
    private String presetName;
    private String lutPath;

    private VideoFilterJob.ProcessingStatus status;
    private Integer progressPercentage;
}