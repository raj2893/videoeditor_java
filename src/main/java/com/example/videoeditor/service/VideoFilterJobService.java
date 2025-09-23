package com.example.videoeditor.service;

import com.example.videoeditor.dto.VideoFilterJobRequest;
import com.example.videoeditor.dto.VideoFilterJobResponse;
import com.example.videoeditor.entity.User;

import java.util.List;

public interface VideoFilterJobService {

    /**
     * Create a new filter job using an uploaded video.
     * @param uploadId ID of the uploaded video
     * @param request filter parameters
     * @param user the current user
     */
    VideoFilterJobResponse createJobFromUpload(Long uploadId, VideoFilterJobRequest request, User user);

    /**
     * Get a filter job by ID for the current user
     */
    VideoFilterJobResponse getJob(Long jobId, User user);

    /**
     * Get all filter jobs for a user
     */
    List<VideoFilterJobResponse> getJobsByUser(User user);

    /**
     * Optional: start processing a job (async FFmpeg)
     */
    void processJob(Long jobId, User user);

    VideoFilterJobResponse updateJob(Long jobId, VideoFilterJobRequest request, User user);

}
