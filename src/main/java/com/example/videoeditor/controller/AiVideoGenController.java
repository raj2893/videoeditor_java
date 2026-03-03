package com.example.videoeditor.controller;

import com.example.videoeditor.entity.AiVideoGen;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.enums.VideoGenModel;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.security.JwtUtil;
import com.example.videoeditor.service.AiVideoGenService;
import com.example.videoeditor.service.VideoGenPlanService;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
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
    private final VideoGenPlanService planService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    private final String baseDir = "D:\\Backend\\videoeditor_java";

    public AiVideoGenController(
            AiVideoGenService videoGenService,
            VideoGenPlanService planService,
            JwtUtil jwtUtil,
            UserRepository userRepository) {
        this.videoGenService = videoGenService;
        this.planService = planService;
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

            if (!planService.hasAnyVideoGenPlan(user)) {
                return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                        .body("No active AI Video Generation plan. Please purchase a plan.");
            }

            List<VideoGenModel> availableModels = planService.getAvailableModels(user);

            List<Map<String, Object>> modelList = availableModels.stream().map(model -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", model.name());
                m.put("displayName", model.getDisplayName());
                m.put("tier", model.getTier().name());
                m.put("resolution", model.getResolution());
                m.put("supportsAudio", model.isSupportsAudio());
                m.put("creditsPerFiveSeconds", model.getCreditsPerFiveSeconds());
                m.put("creditsPer10Seconds", model.getCreditsPerFiveSeconds() * 2);
                m.put("description", model.getDescription());
                return m;
            }).collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "models", modelList,
                    "activePlan", planService.getActivePlanName(user),
                    "maxDurationSeconds", planService.getMaxDurationSeconds(user)
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

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("monthlyLimit", planService.getMonthlyCredits(user));
            response.put("monthlyUsed", videoGenService.getMonthlyCreditsUsed(user));
            response.put("monthlyRemaining", videoGenService.getRemainingMonthlyCredits(user));
            response.put("dailyLimit", planService.getDailyCredits(user));
            response.put("dailyRemaining", videoGenService.getRemainingDailyCredits(user));
            response.put("activePlan", planService.getActivePlanName(user));
            response.put("hasVideoPlan", planService.hasAnyVideoGenPlan(user));

            // Credit cost reference table for frontend
            response.put("creditCosts", buildCreditCostReference());

            return ResponseEntity.ok(response);

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

            String modelId = (String) request.get("model");
            String prompt = (String) request.get("prompt");
            String negativePrompt = (String) request.getOrDefault("negativePrompt", null);
            int durationSeconds = ((Number) request.getOrDefault("durationSeconds", 5)).intValue();
            boolean audioEnabled = (Boolean) request.getOrDefault("audioEnabled", false);
            String aspectRatio = (String) request.getOrDefault("aspectRatio", "16:9");

            VideoGenModel model = parseModel(modelId);

            AiVideoGen job = videoGenService.submitTextToVideo(
                    user, model, prompt, negativePrompt,
                    durationSeconds, audioEnabled, aspectRatio);

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
            @RequestParam("image") MultipartFile imageFile) {
        try {
            User user = getUserFromToken(token);
            VideoGenModel model = parseModel(modelId);

            // Upload image to fal.ai storage — returns a CDN URL
            String falImageUrl = videoGenService.uploadImageToFal(imageFile);

            AiVideoGen job = videoGenService.submitImageToVideo(
                    user, model, prompt, falImageUrl, null,
                    durationSeconds, audioEnabled, aspectRatio);

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
            // Authenticate (we still want to prevent unauthorized polling)
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
        // Only include error message if failed
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

    private String saveUploadedImage(User user, MultipartFile imageFile) throws IOException {
        File dir = new File(baseDir, "video_ref_images" + File.separator + user.getId());
        dir.mkdirs();
        String fileName = "ref_" + System.currentTimeMillis() + "_" + imageFile.getOriginalFilename();
        File savedFile = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(savedFile)) {
            fos.write(imageFile.getBytes());
        }
        return "images/video_ref/" + user.getId() + "/" + fileName;
    }

    /**
     * Returns a credit cost reference table for the frontend to display
     * "This will cost X credits" before user confirms generation.
     */
    private List<Map<String, Object>> buildCreditCostReference() {
        List<Map<String, Object>> costs = new ArrayList<>();
        for (VideoGenModel model : VideoGenModel.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("model", model.name());
            entry.put("displayName", model.getDisplayName());
            entry.put("5s_no_audio", model.calculateCredits(5, false));
            entry.put("5s_with_audio", model.isSupportsAudio() ? model.calculateCredits(5, true) : "N/A");
            entry.put("10s_no_audio", model.calculateCredits(10, false));
            entry.put("10s_with_audio", model.isSupportsAudio() ? model.calculateCredits(10, true) : "N/A");
            costs.add(entry);
        }
        return costs;
    }

    private User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}