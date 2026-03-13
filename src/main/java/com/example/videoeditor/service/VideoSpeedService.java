package com.example.videoeditor.service;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.UserProcessingUsage;
import com.example.videoeditor.entity.VideoSpeed;
import com.example.videoeditor.repository.UserProcessingUsageRepository;
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
import java.time.YearMonth;
import java.util.*;

@Service
@RequiredArgsConstructor
public class VideoSpeedService {
    private static final Logger logger = LoggerFactory.getLogger(VideoSpeedService.class);

    private final VideoSpeedRepository videoSpeedRepository;
    private final UserProcessingUsageRepository userProcessingUsageRepository;
    private final ObjectMapper objectMapper;
    private final PlanLimitsService planLimitsService;

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

        try {
            double videoDuration = getVideoDuration(filePath);
            int maxMinutes = planLimitsService.getMaxSpeedVideoLengthMinutes(user);

            if (maxMinutes > 0 && videoDuration > maxMinutes * 60) {
                // Delete the uploaded file since it exceeds limits
                Files.deleteIfExists(Paths.get(filePath));
                throw new IllegalArgumentException(
                        "Video length (" + (int)(videoDuration/60) + " min) exceeds maximum allowed (" +
                                maxMinutes + " minutes). Upgrade your plan."
                );
            }
        } catch (InterruptedException e) {
            Files.deleteIfExists(Paths.get(filePath));
            Thread.currentThread().interrupt();
            throw new IOException("Failed to validate video duration: " + e.getMessage());
        }

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
    public VideoSpeed initiateExport(Long id, User user, String quality) throws IOException, InterruptedException {
        VideoSpeed video = videoSpeedRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new RuntimeException("Video not found or unauthorized: " + id));

        if ("PENDING".equals(video.getStatus()) || "PROCESSING".equals(video.getStatus())) {
            throw new IllegalStateException("Video is already being processed");
        }

        Path filePath = Paths.get(video.getOriginalFilePath());
        if (!Files.exists(filePath)) {
            throw new IllegalStateException("Original video file not found: " + filePath);
        }

        // Get video duration and validate limits
        double videoDuration = getVideoDuration(video.getOriginalFilePath());
        validateProcessingLimits(user, quality, videoDuration);

        // Set quality
        String finalQuality = quality != null ? quality : "720p";
        video.setQuality(finalQuality);

        video.setStatus("PENDING");
        video.setProgress(10.0);
        video.setCdnUrl(null);
        video.setOutputFilePath(null);
        video.setLastModified(LocalDateTime.now());
        videoSpeedRepository.save(video);

        processVideoWithFFmpeg(video, finalQuality, planLimitsService.shouldAddWatermark(user));

        return video;
    }

    private void processVideoWithFFmpeg(VideoSpeed video, String quality, boolean addWatermark) throws IOException {
        String inputPath = video.getOriginalFilePath();
        String outputFileName = "output_" + System.currentTimeMillis() + ".mp4";
        Path outputDir = Paths.get(basePath, video.getUser().getId().toString());
        Files.createDirectories(outputDir);
        String outputPath = outputDir.resolve(outputFileName).toString();

        video.setStatus("PROCESSING");
        video.setProgress(20.0);
        videoSpeedRepository.save(video);

        try {
            File ffmpegFile = new File(ffmpegPath);
            if (!ffmpegFile.exists() || !ffmpegFile.canExecute()) {
                throw new IOException("FFmpeg executable not found or not executable: " + ffmpegPath);
            }

            Map<String, String> qualitySettings = getFFmpegQualitySettings(quality);
            String videoFilter = String.format("setpts=%f*PTS,scale=%s", 1.0 / video.getSpeed(), qualitySettings.get("scale"));
            if (addWatermark) {
                String fontPath = getClass().getClassLoader()
                        .getResource("fonts/LexendGiga-Bold.ttf")
                        .getPath()
                        .replaceFirst("^/", "")
                        .replace("/", "\\\\")
                        .replace(":", "\\:");
                videoFilter += String.format(
                        ",drawtext=text='SCENITH':fontfile='%s':fontcolor=white:fontsize=h/20:alpha=0.75:x=w-tw-20:y=20",
                        fontPath
                );
            }

            String ffmpegCommand = String.format(
                    "%s -i \"%s\" -filter:v \"%s\" -filter:a \"atempo=%f\" -c:v libx264 -preset %s -crf %s -c:a aac -y \"%s\"",
                    ffmpegPath,
                    inputPath,
                    videoFilter,
                    video.getSpeed(),
                    qualitySettings.get("preset"),
                    qualitySettings.get("crf"),
                    outputPath
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
                    incrementUsageCount(video.getUser());
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

    private void validateProcessingLimits(User user, String quality, double videoDuration) throws IllegalArgumentException {
        if (quality != null && !planLimitsService.isSpeedQualityAllowed(user, quality)) {
            throw new IllegalArgumentException("Quality " + quality + " not allowed. Maximum allowed: " +
                    planLimitsService.getMaxSpeedAllowedQuality(user));
        }

        int maxPerMonth = planLimitsService.getMaxSpeedProcessingPerMonth(user);
        if (maxPerMonth > 0) {
            String currentYearMonth = YearMonth.now().toString();
            Optional<UserProcessingUsage> usageOpt = userProcessingUsageRepository.findByUserAndServiceTypeAndYearMonth(
                    user, "VIDEO_SPEED", currentYearMonth);

            int currentCount = usageOpt.map(UserProcessingUsage::getProcessCount).orElse(0);
            if (currentCount >= maxPerMonth) {
                throw new IllegalArgumentException("Monthly processing limit reached (" + maxPerMonth + "). Upgrade your plan for more.");
            }
        }

        int maxMinutes = planLimitsService.getMaxSpeedVideoLengthMinutes(user);
        if (maxMinutes > 0 && videoDuration > maxMinutes * 60) {
            throw new IllegalArgumentException("Video length exceeds maximum allowed (" + maxMinutes + " minutes). Upgrade your plan.");
        }
    }

    private void incrementUsageCount(User user) {
        String currentYearMonth = YearMonth.now().toString();
        Optional<UserProcessingUsage> usageOpt = userProcessingUsageRepository.findByUserAndServiceTypeAndYearMonth(
                user, "VIDEO_SPEED", currentYearMonth);

        UserProcessingUsage usage;
        if (usageOpt.isPresent()) {
            usage = usageOpt.get();
            usage.setProcessCount(usage.getProcessCount() + 1);
        } else {
            usage = new UserProcessingUsage();
            usage.setUser(user);
            usage.setServiceType("VIDEO_SPEED");
            usage.setYearMonth(currentYearMonth);
            usage.setProcessCount(1);
        }
        userProcessingUsageRepository.save(usage);
    }

    private Map<String, String> getFFmpegQualitySettings(String quality) {
        Map<String, String> settings = new HashMap<>();
        switch (quality != null ? quality.toLowerCase() : "720p") {
            case "144p":
                settings.put("scale", "-2:144");
                settings.put("crf", "28");
                settings.put("preset", "veryfast");
                break;
            case "240p":
                settings.put("scale", "-2:240");
                settings.put("crf", "27");
                settings.put("preset", "veryfast");
                break;
            case "360p":
                settings.put("scale", "-2:360");
                settings.put("crf", "26");
                settings.put("preset", "fast");
                break;
            case "480p":
                settings.put("scale", "-2:480");
                settings.put("crf", "25");
                settings.put("preset", "fast");
                break;
            case "720p":
                settings.put("scale", "-2:720");
                settings.put("crf", "23");
                settings.put("preset", "medium");
                break;
            case "1080p":
                settings.put("scale", "-2:1080");
                settings.put("crf", "22");
                settings.put("preset", "medium");
                break;
            case "1440p":
            case "2k":
                settings.put("scale", "-2:1440");
                settings.put("crf", "20");
                settings.put("preset", "slow");
                break;
            case "4k":
                settings.put("scale", "-2:2160");
                settings.put("crf", "18");
                settings.put("preset", "slow");
                break;
            default:
                settings.put("scale", "-2:720");
                settings.put("crf", "23");
                settings.put("preset", "medium");
        }
        return settings;
    }

    private double getVideoDuration(String videoPath) throws IOException, InterruptedException {
        List<String> command = Arrays.asList(
                ffmpegPath.replace("ffmpeg.exe", "ffprobe.exe"),
                "-i", videoPath,
                "-show_entries", "format=duration",
                "-v", "quiet",
                "-of", "json"
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        process.waitFor();

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> result = mapper.readValue(output.toString(), Map.class);
        Map<String, Object> format = (Map<String, Object>) result.get("format");
        return Double.parseDouble(format.get("duration").toString());
    }
}