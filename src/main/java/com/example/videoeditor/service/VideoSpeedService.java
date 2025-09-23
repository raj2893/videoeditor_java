package com.example.videoeditor.service;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.VideoSpeed;
import com.example.videoeditor.repository.VideoSpeedRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VideoSpeedService {
    private static final Logger logger = LoggerFactory.getLogger(VideoSpeedService.class);

    private final VideoSpeedRepository videoSpeedRepository;
    private final ObjectMapper objectMapper;

    @Value("${video-editor.base-path-speed}")
    private String basePath;

    @Value("${video-editor.ffmpeg-path}")
    private String ffmpegPath;

    @Transactional
    public VideoSpeed uploadVideo(User user, MultipartFile videoFile, Double speed) throws IOException {
        // Validate file
        if (videoFile == null || videoFile.isEmpty()) {
            throw new IllegalArgumentException("Video file cannot be empty");
        }

        // Validate file type
        String contentType = videoFile.getContentType();
        if (!isValidVideoType(contentType)) {
            throw new IllegalArgumentException("Invalid file type. Only video files (mp4, mov, avi) are allowed.");
        }

        // Validate speed
        if (speed != null && (speed < 0.5 || speed > 15.0)) {
            throw new IllegalArgumentException("Speed must be between 0.5 and 15.0");
        }

        String originalFileName = sanitizeFilename(videoFile.getOriginalFilename());
        Path uploadDir = Paths.get(basePath, user.getId().toString());
        Files.createDirectories(uploadDir); // Create user directory if it doesn't exist
        String filePath = uploadDir.resolve(originalFileName).toString();

        // Save file to local storage
        videoFile.transferTo(new File(filePath));

        // Create VideoSpeed entity
        VideoSpeed video = new VideoSpeed();
        video.setUser(user);
        video.setOriginalFilePath(filePath);
        video.setSpeed(speed != null ? speed : 1.0);
        video.setStatus("UPLOADED");
        video.setProgress(0.0);
        video.setCreatedAt(LocalDateTime.now());
        video.setLastModified(LocalDateTime.now());
        return videoSpeedRepository.save(video);
    }

    @Transactional
    public VideoSpeed updateSpeed(Long id, User user, Double speed) {
        VideoSpeed video = videoSpeedRepository.findByIdAndUser(id, user)
            .orElseThrow(() -> new RuntimeException("Video not found or unauthorized: " + id));
        if (speed == null || speed < 0.5 || speed > 15.0) {
            throw new IllegalArgumentException("Speed must be between 0.5 and 15.0");
        }
        if ("PROCESSING".equals(video.getStatus()) || "PENDING".equals(video.getStatus())) {
            throw new IllegalStateException("Cannot update speed while video is being processed");
        }
        video.setSpeed(speed);
        video.setLastModified(LocalDateTime.now());
        return videoSpeedRepository.save(video);
    }

    @Transactional
    public VideoSpeed initiateExport(Long id, User user) throws IOException {
        VideoSpeed video = videoSpeedRepository.findByIdAndUser(id, user)
            .orElseThrow(() -> new RuntimeException("Video not found or unauthorized: " + id));

        // Allow export for UPLOADED, FAILED, or COMPLETED videos
        if ("PENDING".equals(video.getStatus()) || "PROCESSING".equals(video.getStatus())) {
            throw new IllegalStateException("Video is already being processed");
        }

        // Verify original file exists
        Path filePath = Paths.get(video.getOriginalFilePath());
        if (!Files.exists(filePath)) {
            throw new IllegalStateException("Original video file not found: " + filePath);
        }

        // Reset fields for re-export
        video.setStatus("PENDING");
        video.setProgress(10.0);
        video.setCdnUrl(null);
        video.setOutputFilePath(null);
        video.setLastModified(LocalDateTime.now());
        videoSpeedRepository.save(video);

        // Process video with FFmpeg
        processVideoWithFFmpeg(video);

        return video;
    }

    private void processVideoWithFFmpeg(VideoSpeed video) throws IOException {
        String inputPath = video.getOriginalFilePath();
        String outputFileName = "output_" + System.currentTimeMillis() + ".mp4";
        Path outputDir = Paths.get(basePath, video.getUser().getId().toString());
        Files.createDirectories(outputDir);
        String outputPath = outputDir.resolve(outputFileName).toString();

        video.setStatus("PROCESSING");
        video.setProgress(20.0);
        videoSpeedRepository.save(video);

        try {
            // Validate FFmpeg path
            File ffmpegFile = new File(ffmpegPath);
            if (!ffmpegFile.exists() || !ffmpegFile.canExecute()) {
                throw new IOException("FFmpeg executable not found or not executable: " + ffmpegPath);
            }

            // Build FFmpeg command
            String ffmpegCommand = String.format(
                "%s -i %s -filter:v setpts=%f*PTS -c:v libx264 -c:a aac -y %s",
                ffmpegPath, inputPath, 1.0 / video.getSpeed(), outputPath
            );
            logger.info("Executing FFmpeg command: {}", ffmpegCommand);

            // Execute FFmpeg process
            ProcessBuilder processBuilder = new ProcessBuilder(ffmpegCommand.split("\\s+"));
            processBuilder.redirectErrorStream(true); // Merge stdout and stderr
            Process process = processBuilder.start();

            // Consume FFmpeg output to prevent hanging
            StringBuilder ffmpegOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    ffmpegOutput.append(line).append("\n");
                }
            }

            // Wait for process to complete with timeout (e.g., 5 minutes)
            boolean completed = process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
            int exitCode = process.exitValue();

            if (completed && exitCode == 0) {
                // Verify output file exists
                if (Files.exists(Paths.get(outputPath))) {
                    video.setStatus("COMPLETED");
                    video.setProgress(100.0);
                    video.setOutputFilePath(outputPath);
                    video.setCdnUrl(outputPath); // Set cdnUrl to local output path
                } else {
                    video.setStatus("FAILED");
                    video.setProgress(0.0);
                    logger.error("FFmpeg completed but output file not found: {}", outputPath);
                }
            } else {
                video.setStatus("FAILED");
                video.setProgress(0.0);
                logger.error("FFmpeg processing failed for videoId={}. Exit code: {}. Output: {}",
                    video.getId(), exitCode, ffmpegOutput.toString());
            }
        } catch (InterruptedException e) {
            video.setStatus("FAILED");
            video.setProgress(0.0);
            logger.error("FFmpeg processing interrupted for videoId={}: {}", video.getId(), e.getMessage(), e);
            Thread.currentThread().interrupt(); // Restore interrupted status
        } catch (Exception e) {
            video.setStatus("FAILED");
            video.setProgress(0.0);
            logger.error("FFmpeg processing failed for videoId={}: {}", video.getId(), e.getMessage(), e);
        } finally {
            video.setLastModified(LocalDateTime.now());
            videoSpeedRepository.save(video);
        }
    }

    public VideoSpeed getVideoStatus(Long id, User user) {
        return videoSpeedRepository.findByIdAndUser(id, user)
            .orElseThrow(() -> new RuntimeException("Video not found or unauthorized: " + id));
    }

    public List<VideoSpeed> getUserVideos(User user) {
        return videoSpeedRepository.findByUser(user);
    }

    private String sanitizeFilename(String filename) {
        if (filename == null) return "video_" + System.currentTimeMillis() + ".mp4";
        return filename.toLowerCase().replaceAll("[^a-z0-9._-]", "_");
    }

    private boolean isValidVideoType(String contentType) {
        return contentType != null && (
            contentType.equals("video/mp4") ||
                contentType.equals("video/mov") ||
                contentType.equals("video/avi")
        );
    }
}