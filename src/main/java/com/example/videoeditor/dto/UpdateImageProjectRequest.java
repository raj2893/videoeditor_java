package com.example.videoeditor.dto;

import lombok.Data;

@Data
public class UpdateImageProjectRequest {  // Remove 'public'
    private String projectName;
    private String designJson;
}
