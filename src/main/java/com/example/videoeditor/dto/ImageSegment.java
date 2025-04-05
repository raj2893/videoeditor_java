package com.example.videoeditor.dto;

import lombok.Data;

import java.util.*;

@Data
public class ImageSegment {
    private String id = UUID.randomUUID().toString();
    private String imagePath;
    private int layer;
    private int positionX;
    private int positionY;
    private double scale;
    private double opacity = 1.0;
    private double timelineStartTime;
    private double timelineEndTime;
    private int width;
    private int height;
    private int customWidth;
    private int customHeight;
    private boolean maintainAspectRatio = true;
    private Map<String, String> filters = new HashMap<>();

    // Keyframes for animatable properties
    private Map<String, List<Keyframe>> keyframes = new HashMap<>();

    public Map<String, List<Keyframe>> getKeyframes() {
        return keyframes;
    }

    public void setKeyframes(Map<String, List<Keyframe>> keyframes) {
        this.keyframes = keyframes;
    }

    public void addKeyframe(String property, Keyframe keyframe) {
        keyframes.computeIfAbsent(property, k -> new ArrayList<>()).add(keyframe);
        keyframes.get(property).sort(Comparator.comparingDouble(Keyframe::getTime));
    }

    public void removeKeyframe(String property, double time) {
        List<Keyframe> propertyKeyframes = keyframes.get(property);
        if (propertyKeyframes != null) {
            propertyKeyframes.removeIf(kf -> kf.getTime() == time);
        }
    }

    // Existing methods remain unchanged...
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }
    public int getLayer() { return layer; }
    public void setLayer(int layer) { this.layer = layer; }
    public int getPositionX() { return positionX; }
    public void setPositionX(int positionX) { this.positionX = positionX; }
    public int getPositionY() { return positionY; }
    public void setPositionY(int positionY) { this.positionY = positionY; }
    public double getScale() { return scale; }
    public void setScale(double scale) { this.scale = scale; }
    public double getOpacity() { return opacity; }
    public void setOpacity(double opacity) { this.opacity = opacity; }
    public double getTimelineStartTime() { return timelineStartTime; }
    public void setTimelineStartTime(double timelineStartTime) { this.timelineStartTime = timelineStartTime; }
    public double getTimelineEndTime() { return timelineEndTime; }
    public void setTimelineEndTime(double timelineEndTime) { this.timelineEndTime = timelineEndTime; }
    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }
    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }
    public int getCustomWidth() { return customWidth; }
    public void setCustomWidth(int customWidth) { this.customWidth = customWidth; }
    public int getCustomHeight() { return customHeight; }
    public void setCustomHeight(int customHeight) { this.customHeight = customHeight; }
    public boolean isMaintainAspectRatio() { return maintainAspectRatio; }
    public void setMaintainAspectRatio(boolean maintainAspectRatio) { this.maintainAspectRatio = maintainAspectRatio; }
    public Map<String, String> getFilters() { return filters; }
    public void setFilters(Map<String, String> filters) { this.filters = filters; }
    public void addFilter(String filterType, String filterValue) { this.filters.put(filterType, filterValue); }
    public void removeFilter(String filterType) { this.filters.remove(filterType); }
    public int getEffectiveWidth() { return customWidth > 0 ? customWidth : (int) (width * scale); }
    public int getEffectiveHeight() { return customHeight > 0 ? customHeight : (int) (height * scale); }
}