package com.example.videoeditor.dto;

import lombok.Data;

import java.util.*;

@Data
public class AudioSegment {
    private String id = UUID.randomUUID().toString();
    private String audioPath;
    private int layer; // Will be negative (-1, -2, -3, etc.)
    private double startTime;
    private double endTime;
    private double timelineStartTime;
    private double timelineEndTime;
    private double volume = 1.0;

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

    // Existing getters and setters remain unchanged...
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAudioPath() { return audioPath; }
    public void setAudioPath(String audioPath) { this.audioPath = audioPath; }
    public int getLayer() { return layer; }
    public void setLayer(int layer) { this.layer = layer; }
    public double getStartTime() { return startTime; }
    public void setStartTime(double startTime) { this.startTime = startTime; }
    public double getEndTime() { return endTime; }
    public void setEndTime(double endTime) { this.endTime = endTime; }
    public double getTimelineStartTime() { return timelineStartTime; }
    public void setTimelineStartTime(double timelineStartTime) { this.timelineStartTime = timelineStartTime; }
    public double getTimelineEndTime() { return timelineEndTime; }
    public void setTimelineEndTime(double timelineEndTime) { this.timelineEndTime = timelineEndTime; }
    public double getVolume() { return volume; }
    public void setVolume(double volume) { this.volume = volume; }
}