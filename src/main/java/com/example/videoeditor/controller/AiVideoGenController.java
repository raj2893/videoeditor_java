package com.example.videoeditor.controller;

import com.example.videoeditor.entity.AiVideoGen;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.enums.VideoGenModel;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.security.JwtUtil;
import com.example.videoeditor.service.AiVideoGenService;
import com.example.videoeditor.service.CreditService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST Controller for AI Video Generation.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Endpoints                                                               │
 * │                                                                          │
 * │  GET  /api/video-gen/models          → List of available models for UI  │
 * │  GET  /api/video-gen/credits         → User's credit balance            │
 * │  POST /api/video-gen/text-to-video   → Submit text-to-video job         │
 * │  POST /api/video-gen/image-to-video  → Submit image-to-video job        │
 * │  GET  /api/video-gen/status/{id}     → Poll generation status           │
 * │  GET  /api/video-gen/history         → User's generation history        │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Frontend polling flow:
 *   1. User submits → POST /text-to-video → get back { falRequestId, status: "PENDING" }
 *   2. Frontend polls GET /status/{falRequestId} every 5 seconds
 *   3. When status = "COMPLETED", show the video at videoPath
 */
@RestController
@RequestMapping("/api/video-gen")
public class AiVideoGenController {

    private final AiVideoGenService videoGenService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final CreditService creditService;

    private final String baseDir = "D:\\Backend\\videoeditor_java";

    public AiVideoGenController(
            AiVideoGenService videoGenService,
            JwtUtil jwtUtil,
            UserRepository userRepository,
            CreditService creditService) {
        this.videoGenService = videoGenService;
        this.creditService = creditService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /models — models dropdown data for frontend
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/models")
    public ResponseEntity<?> getAvailableModels(@RequestHeader("Authorization") String token) {
        try {
            User user = getUserFromToken(token);

            List<Map<String, Object>> modelList = Arrays.stream(VideoGenModel.values()).map(model -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", model.name());
                m.put("displayName", model.getDisplayName());
                m.put("defaultResolution", model.getDefaultResolution());
                m.put("supportsAudio", model.isSupportsAudio());
                m.put("description", model.getDescription());
                // Include the full credit cost matrix for the frontend to display
                // "This will cost X credits" before the user confirms
                m.put("creditCosts", buildModelCreditMatrix(model));
                return m;
            }).toList();

            return ResponseEntity.ok(Map.of(
                    "models", modelList,
                    "balance", creditService.getBalance(user),
                    "maxDurationSeconds", 10
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Unauthorized: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /credits — user's current credit balance
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/credits")
    public ResponseEntity<?> getCredits(@RequestHeader("Authorization") String token) {
        try {
            User user = getUserFromToken(token);
            return ResponseEntity.ok(Map.of(
                    "balance", creditService.getBalance(user),
                    "planType", user.getPlanType(),
                    "expiresAt", user.getPlanExpiresAt() != null ? user.getPlanExpiresAt() : "N/A",
                    "creditCosts", buildFullCreditCostReference()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Unauthorized: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /text-to-video — submit a new generation job
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/text-to-video")
    public ResponseEntity<?> submitTextToVideo(
            @RequestHeader("Authorization") String token,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String modelId        = (String) request.get("model");
            String prompt         = (String) request.get("prompt");
            String negativePrompt = (String) request.getOrDefault("negativePrompt", null);
            int durationSeconds   = ((Number) request.getOrDefault("durationSeconds", 5)).intValue();
            boolean audioEnabled  = (Boolean) request.getOrDefault("audioEnabled", false);
            String aspectRatio    = (String) request.getOrDefault("aspectRatio", "16:9");
            // Resolution: only user-selectable for Wan 2.5. Defaults to model's default otherwise.
            String resolution     = (String) request.getOrDefault("resolution", null);

            VideoGenModel model = parseModel(modelId);

            AiVideoGen job = videoGenService.submitTextToVideo(
                    user, model, prompt, negativePrompt,
                    durationSeconds, audioEnabled, aspectRatio, resolution);

            return ResponseEntity.ok(buildJobResponse(job));

        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to submit generation job: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Unauthorized: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /image-to-video — submit image-to-video job
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/image-to-video")
    public ResponseEntity<?> submitImageToVideo(
            @RequestHeader("Authorization") String token,
            @RequestParam("model") String modelId,
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "durationSeconds", defaultValue = "5") int durationSeconds,
            @RequestParam(value = "audioEnabled", defaultValue = "false") boolean audioEnabled,
            @RequestParam(value = "aspectRatio", defaultValue = "16:9") String aspectRatio,
            // Resolution only matters for Wan 2.5 — ignored for other models
            @RequestParam(value = "resolution", required = false) String resolution,
            @RequestParam("image") MultipartFile imageFile) {
        try {
            User user = getUserFromToken(token);
            VideoGenModel model = parseModel(modelId);

            String falImageUrl = videoGenService.uploadImageToFal(imageFile);

            AiVideoGen job = videoGenService.submitImageToVideo(
                    user, model, prompt, falImageUrl, null,
                    durationSeconds, audioEnabled, aspectRatio, resolution);

            return ResponseEntity.ok(buildJobResponse(job));

        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to upload image or submit job: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Unauthorized: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /status/{falRequestId} — poll generation status
    // Frontend should call this every 5 seconds until status = COMPLETED/FAILED
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/status/{falRequestId}")
    public ResponseEntity<?> getStatus(
            @RequestHeader("Authorization") String token,
            @PathVariable String falRequestId) {
        try {
            getUserFromToken(token);
            AiVideoGen job = videoGenService.checkAndUpdateStatus(falRequestId);
            return ResponseEntity.ok(buildJobResponse(job));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error checking status: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Unauthorized: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /history — user's past generations
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(@RequestHeader("Authorization") String token) {
        try {
            User user = getUserFromToken(token);
            List<AiVideoGen> history = videoGenService.getUserHistory(user);
            List<Map<String, Object>> result = history.stream()
                    .map(this::buildJobResponse)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Unauthorized: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> buildJobResponse(AiVideoGen job) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", job.getId());
        map.put("falRequestId", job.getFalRequestId());
        map.put("model", job.getModel().name());
        map.put("modelDisplayName", job.getModel().getDisplayName());
        map.put("generationType", job.getGenerationType().name());
        map.put("status", job.getStatus().name());
        map.put("prompt", job.getPrompt());
        map.put("durationSeconds", job.getDurationSeconds());
        map.put("audioEnabled", job.getAudioEnabled());
        map.put("aspectRatio", job.getAspectRatio());
        map.put("creditsUsed", job.getCreditsUsed());
        map.put("resolution", job.getResolution());
        map.put("videoPath", job.getVideoPath());
        map.put("createdAt", job.getCreatedAt());
        map.put("completedAt", job.getCompletedAt());
        if (job.getStatus() == AiVideoGen.Status.FAILED) {
            map.put("errorMessage", job.getErrorMessage());
        }
        return map;
    }

    private VideoGenModel parseModel(String modelId) {
        try {
            return VideoGenModel.valueOf(modelId.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown model: " + modelId
                    + ". Valid models: " + Arrays.toString(VideoGenModel.values()));
        }
    }

    /**
     * Builds a full credit cost matrix for a single model.
     * Used in the /models response so the frontend can show
     * "This will cost X credits" before the user confirms.
     *
     * Example output for Wan 2.5:
     *   [
     *     { resolution: "480p", duration: 5, audio: false, credits: 46 },
     *     { resolution: "480p", duration: 10, audio: false, credits: 92 },
     *     ...
     *   ]
     */
    private List<Map<String, Object>> buildModelCreditMatrix(VideoGenModel model) {
        List<Map<String, Object>> matrix = new ArrayList<>();

        if (model == VideoGenModel.WAN_2_5) {
            // Wan 2.5: resolution-variable, no audio
            for (String res : List.of("480p", "720p", "1080p")) {
                for (int dur : List.of(5, 10)) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("resolution", res);
                    entry.put("duration", dur);
                    entry.put("audio", false);
                    entry.put("credits", model.calculateCredits(dur, false, res));
                    matrix.add(entry);
                }
            }
        } else {
            // All other models: fixed resolution, audio on/off where supported
            String res = model.getDefaultResolution();
            for (int dur : List.of(5, 10)) {
                // Audio off
                Map<String, Object> off = new LinkedHashMap<>();
                off.put("resolution", res);
                off.put("duration", dur);
                off.put("audio", false);
                off.put("credits", model.calculateCredits(dur, false, res));
                matrix.add(off);

                // Audio on (only for models that support it)
                if (model.isSupportsAudio()) {
                    Map<String, Object> on = new LinkedHashMap<>();
                    on.put("resolution", res);
                    on.put("duration", dur);
                    on.put("audio", true);
                    on.put("credits", model.calculateCredits(dur, true, res));
                    matrix.add(on);
                }
            }
        }

        return matrix;
    }

    /**
     * Returns the full credit cost reference for all models — used in /credits response.
     */
    private List<Map<String, Object>> buildFullCreditCostReference() {
        return Arrays.stream(VideoGenModel.values())
                .map(model -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("model", model.name());
                    entry.put("displayName", model.getDisplayName());
                    entry.put("costs", buildModelCreditMatrix(model));
                    return entry;
                })
                .collect(Collectors.toList());
    }

    private User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}