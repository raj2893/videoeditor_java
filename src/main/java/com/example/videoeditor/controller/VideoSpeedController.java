package com.example.videoeditor.controller;

import com.example.videoeditor.dto.VideoSpeedRequest;
import com.example.videoeditor.dto.VideoSpeedResponse;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.UserProcessingUsage;
import com.example.videoeditor.entity.VideoSpeed;
import com.example.videoeditor.repository.UserProcessingUsageRepository;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.security.JwtUtil;
import com.example.videoeditor.service.PlanLimitsService;
import com.example.videoeditor.service.VideoSpeedService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/video-speed")
@RequiredArgsConstructor
public class VideoSpeedController {
    private static final Logger logger = LoggerFactory.getLogger(VideoSpeedController.class);

    private final VideoSpeedService videoSpeedService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final PlanLimitsService planLimitsService;
    private final UserProcessingUsageRepository userProcessingUsageRepository;

    private User getUserFromToken(String token) {
        if (token == null || !token.startsWith("Bearer ")) {
            throw new RuntimeException("Invalid or missing Authorization token");
        }
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadVideo(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "speed", required = false) Double speed) {
        try {
            User user = getUserFromToken(token);
            VideoSpeed video = videoSpeedService.uploadVideo(user, file, speed);
            VideoSpeedResponse response = mapToResponse(video);
            logger.info("Video uploaded: id={}", video.getId());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid input: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            logger.error("Error uploading video: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to upload video: " + e.getMessage()));
        } catch (RuntimeException e) {
            logger.warn("Unauthorized: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}/speed")
    public ResponseEntity<?> updateSpeed(
        @RequestHeader("Authorization") String token,
        @PathVariable Long id,
        @Valid @RequestBody VideoSpeedRequest request) {
        try {
            User user = getUserFromToken(token);
            VideoSpeed video = videoSpeedService.updateSpeed(id, user, request.getSpeed());
            VideoSpeedResponse response = mapToResponse(video);
            logger.info("Speed updated: id={}, speed={}", id, request.getSpeed());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            logger.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            logger.warn("Unauthorized or video not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/export")
    public ResponseEntity<?> initiateExport(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id,
            @RequestParam(required = false) String quality) {
        try {
            User user = getUserFromToken(token);
            VideoSpeed video = videoSpeedService.initiateExport(id, user, quality);
            VideoSpeedResponse response = mapToResponse(video);
            logger.info("Export initiated: id={}, quality={}", id, quality);
            return ResponseEntity.ok(response);
        } catch (IOException | InterruptedException e) {
            logger.error("Error initiating export: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to initiate export: " + e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            logger.warn("Cannot initiate export for video id={}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            logger.warn("Unauthorized or video not found for id={}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/status/{id}")
    public ResponseEntity<?> getVideoStatus(
        @RequestHeader("Authorization") String token,
        @PathVariable Long id) {
        try {
            User user = getUserFromToken(token);
            VideoSpeed video = videoSpeedService.getVideoStatus(id, user);
            VideoSpeedResponse response = mapToResponse(video);
            logger.info("Retrieved status: id={}, status={}", id, video.getStatus());
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            logger.warn("Video not found or unauthorized: id={}, error={}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/user-videos")
    public ResponseEntity<?> getUserVideos(
        @RequestHeader("Authorization") String token) {
        try {
            User user = getUserFromToken(token);
            List<VideoSpeed> videos = videoSpeedService.getUserVideos(user);
            List<VideoSpeedResponse> responses = videos.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
            logger.info("Retrieved {} videos for user", responses.size());
            return ResponseEntity.ok(responses);
        } catch (RuntimeException e) {
            logger.warn("Unauthorized: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", e.getMessage()));
        }
    }

    private VideoSpeedResponse mapToResponse(VideoSpeed video) {
        VideoSpeedResponse response = new VideoSpeedResponse();
        response.setId(video.getId());
        response.setStatus(video.getStatus());
        response.setProgress(video.getProgress());
        response.setSpeed(video.getSpeed());
        response.setQuality(video.getQuality());
        response.setCdnUrl(video.getCdnUrl());
        response.setOriginalFilePath(video.getOriginalFilePath());
        return response;
    }

    @GetMapping("/plan-limits")
    public ResponseEntity<?> getPlanLimits(@RequestHeader("Authorization") String token) {
        try {
            User user = getUserFromToken(token);

            int videosPerMonth = planLimitsService.getMaxSpeedProcessingPerMonth(user);
            int maxVideoLength = planLimitsService.getMaxSpeedVideoLengthMinutes(user);
            String maxQuality = planLimitsService.getMaxSpeedAllowedQuality(user);

            // Get current usage
            String currentYearMonth = YearMonth.now().toString();
            Optional<UserProcessingUsage> usageOpt = userProcessingUsageRepository
                    .findByUserAndServiceTypeAndYearMonth(user, "VIDEO_SPEED", currentYearMonth);
            int videosUsed = usageOpt.map(UserProcessingUsage::getProcessCount).orElse(0);

            Map<String, Object> response = new HashMap<>();
            response.put("videosPerMonth", videosPerMonth);
            response.put("videosUsed", videosUsed);
            response.put("maxVideoLength", maxVideoLength);
            response.put("maxQuality", maxQuality);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}