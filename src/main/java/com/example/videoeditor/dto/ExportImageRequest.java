package com.example.videoeditor.dto;

import lombok.Data;

@Data
public class ExportImageRequest {  // Remove 'public'
    private String format;
    private Integer quality;
}
