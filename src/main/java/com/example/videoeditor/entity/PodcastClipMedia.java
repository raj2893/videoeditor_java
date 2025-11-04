package com.example.videoeditor.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "podcast_clip_media")
public class PodcastClipMedia {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "source_url")
    private String sourceUrl; // YouTube URL or null if local upload

    @Column(name = "original_file_name")
    private String originalFileName; // For local uploads

    @Column(name = "original_path")
    private String originalPath; // For local uploads or temp download

    @Column(name = "original_cdn_url")
    private String originalCdnUrl;

    @Column(name = "clips_json", columnDefinition = "TEXT")
    private String clipsJson; // JSON array of clip metadata: [{id, startTime, endTime, viralityScore, processedPath, processedCdnUrl}]

    @Column(name = "status", nullable = false)
    private String status;

    private Double progress;

    public PodcastClipMedia() {
        this.status = "PENDING";
    }
}