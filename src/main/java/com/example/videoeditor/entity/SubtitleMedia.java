package com.example.videoeditor.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "subtitle_media")
public class SubtitleMedia {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @Column(name = "original_path", nullable = false)
    private String originalPath;

    @Column(name = "original_cdn_url")
    private String originalCdnUrl;

    @Column(name = "processed_file_name")
    private String processedFileName;

    @Column(name = "processed_path")
    private String processedPath;

    @Column(name = "processed_cdn_url")
    private String processedCdnUrl;

    @Column(name = "subtitles_json", columnDefinition = "TEXT")
    private String subtitlesJson; // Stores subtitles as JSON array of TextSegment-like objects

    @Column(name = "status", nullable = false)
    private String status;

    private Double progress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_modified")
    private LocalDateTime lastModified;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        lastModified = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        lastModified = LocalDateTime.now();
    }

    @Column(length = 10)
    private String quality; // e.g., "720p", "1080p"

    public SubtitleMedia() {
        this.status = "PENDING";
    }
}