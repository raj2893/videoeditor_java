package com.example.videoeditor.dto;

import lombok.Data;

@Data
public class ShadowDTO {  // Remove 'public'
  private Double offsetX;
  private Double offsetY;
  private Double blur;
  private String color;
}
