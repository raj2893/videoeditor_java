package com.example.videoeditor.dto;

import lombok.Data;

@Data
public class VideoSpeedResponse {
    private Long id;
    private String status;
    private Double progress;
    private Double speed;
    private String cdnUrl;
    private String originalFilePath;
}