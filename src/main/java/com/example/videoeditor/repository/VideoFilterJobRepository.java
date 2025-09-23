package com.example.videoeditor.repository;

import com.example.videoeditor.entity.VideoFilterJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VideoFilterJobRepository extends JpaRepository<VideoFilterJob, Long> {
    List<VideoFilterJob> findByUserId(Long userId);
    Optional<VideoFilterJob> findByUploadedVideoIdAndStatus(Long uploadedVideoId, VideoFilterJob.ProcessingStatus status);
}