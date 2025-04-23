package com.example.videoeditor.dto;

import lombok.Data;

import java.util.*;

@Data
public class TextSegment implements Segment {
    private String id = UUID.randomUUID().toString();
    private String text;
    private String fontFamily = "ARIAL";
    private Double scale = 1.0; // Replaced fontSize with scale
    private String fontColor = "white";
    private String backgroundColor = "transparent";
    private Integer positionX = 0;
    private Integer positionY = 0;
    private Double opacity = 1.0;
    private double timelineStartTime;
    private double timelineEndTime;
    private Integer layer = 0;
    private String alignment = "left"; // New field for text alignment

    // New fields for professional text editing
    private Double backgroundOpacity = 1.0; // Opacity of background (0.0 to 1.0)
    private Integer backgroundBorderWidth = 0; // Border thickness in pixels
    private String backgroundBorderColor = "transparent"; // Border color
    private Integer backgroundPadding = 10; // Padding in pixels
    private String shadowColor = "transparent"; // Shadow color
    private Integer shadowOffsetX = 0; // Shadow X offset in pixels
    private Integer shadowOffsetY = 0; // Shadow Y offset in pixels
    private Double shadowAngle = 0.0; // Shadow angle in degrees (0 to 360)

    private Map<String, List<Keyframe>> keyframes = new HashMap<>();

    // Validate alignment values
    public void setAlignment(String alignment) {
        if (alignment == null || (!alignment.equals("left") && !alignment.equals("right") && !alignment.equals("center"))) {
            throw new IllegalArgumentException("Alignment must be 'left', 'right', or 'center'");
        }
        this.alignment = alignment;
    }

    // Validate background opacity
    public void setBackgroundOpacity(Double backgroundOpacity) {
        if (backgroundOpacity != null && (backgroundOpacity < 0.0 || backgroundOpacity > 1.0)) {
            throw new IllegalArgumentException("Background opacity must be between 0.0 and 1.0");
        }
        this.backgroundOpacity = backgroundOpacity != null ? backgroundOpacity : 1.0;
    }

    // Validate background border width
    public void setBackgroundBorderWidth(Integer backgroundBorderWidth) {
        if (backgroundBorderWidth != null && backgroundBorderWidth < 0) {
            throw new IllegalArgumentException("Background border width must be non-negative");
        }
        this.backgroundBorderWidth = backgroundBorderWidth != null ? backgroundBorderWidth : 0;
    }

    // Validate background padding
    public void setBackgroundPadding(Integer backgroundPadding) {
        if (backgroundPadding != null && backgroundPadding < 0) {
            throw new IllegalArgumentException("Background padding must be non-negative");
        }
        this.backgroundPadding = backgroundPadding != null ? backgroundPadding : 10;
    }

    // Validate shadow angle
    public void setShadowAngle(Double shadowAngle) {
        if (shadowAngle != null && (shadowAngle < 0.0 || shadowAngle > 360.0)) {
            throw new IllegalArgumentException("Shadow angle must be between 0 and 360 degrees");
        }
        this.shadowAngle = shadowAngle != null ? shadowAngle : 0.0;
    }


    public Map<String, List<Keyframe>> getKeyframes() {
        return keyframes;
    }

    public void setKeyframes(Map<String, List<Keyframe>> keyframes) {
        this.keyframes = keyframes;
    }

    public void addKeyframe(String property, Keyframe keyframe) {
        List<Keyframe> propertyKeyframes = keyframes.computeIfAbsent(property, k -> new ArrayList<>());
        propertyKeyframes.removeIf(kf -> Math.abs(kf.getTime() - keyframe.getTime()) < 0.0001);
        propertyKeyframes.add(keyframe);
        propertyKeyframes.sort(Comparator.comparingDouble(Keyframe::getTime));
    }

    public void removeKeyframe(String property, double time) {
        List<Keyframe> propertyKeyframes = keyframes.get(property);
        if (propertyKeyframes != null) {
            propertyKeyframes.removeIf(kf -> Math.abs(kf.getTime() - time) < 0.0001);
        }
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public Integer getLayer() {
        return layer;
    }

    @Override
    public double getTimelineStartTime() {
        return timelineStartTime;
    }

    @Override
    public double getTimelineEndTime() {
        return timelineEndTime;
    }

    public void setId(String id) { this.id = id; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getFontFamily() { return fontFamily; }
    public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; }
    public Double getScale() { return scale; } // Replaced getFontSize
    public void setScale(Double scale) { this.scale = scale; } // Replaced setFontSize
    public String getFontColor() { return fontColor; }
    public void setFontColor(String fontColor) { this.fontColor = fontColor; }
    public String getBackgroundColor() { return backgroundColor; }
    public void setBackgroundColor(String backgroundColor) { this.backgroundColor = backgroundColor; }
    public Integer getPositionX() { return positionX; }
    public void setPositionX(Integer positionX) { this.positionX = positionX; }
    public Integer getPositionY() { return positionY; }
    public void setPositionY(Integer positionY) { this.positionY = positionY; }
    public Double getOpacity() { return opacity; }
    public void setOpacity(Double opacity) { this.opacity = opacity; }
    public void setTimelineStartTime(double timelineStartTime) { this.timelineStartTime = timelineStartTime; }
    public void setTimelineEndTime(double timelineEndTime) { this.timelineEndTime = timelineEndTime; }
    public void setLayer(int layer) { this.layer = layer; }
    public String getAlignment() {
        return alignment;
    }
    public String getBackgroundBorderColor() { return backgroundBorderColor; }
    public void setBackgroundBorderColor(String backgroundBorderColor) { this.backgroundBorderColor = backgroundBorderColor; }
    public String getShadowColor() { return shadowColor; }
    public void setShadowColor(String shadowColor) { this.shadowColor = shadowColor; }
    public Integer getShadowOffsetX() { return shadowOffsetX; }
    public void setShadowOffsetX(Integer shadowOffsetX) { this.shadowOffsetX = shadowOffsetX; }
    public Integer getShadowOffsetY() { return shadowOffsetY; }
    public void setShadowOffsetY(Integer shadowOffsetY) { this.shadowOffsetY = shadowOffsetY; }
    public Double getShadowAngle() { return  shadowAngle;}
    public Integer getBackgroundPadding(){ return backgroundPadding; }
    public Integer getBackgroundBorderWidth(){ return backgroundBorderWidth; }
    public Double getBackgroundOpacity(){ return backgroundOpacity; }
}