package com.example.videoeditor.service;

import com.example.videoeditor.entity.AiVideoGen;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.UserVideoGenCredits;
import com.example.videoeditor.enums.VideoGenModel;
import com.example.videoeditor.repository.AiVideoGenRepository;
import com.example.videoeditor.repository.UserVideoGenCreditsRepository;
import okhttp3.*;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
public class AiVideoGenService {

    private static final Logger logger = LoggerFactory.getLogger(AiVideoGenService.class);

    // fal.ai queue base URL — all video models use this async pattern
    private static final String FAL_QUEUE_URL = "https://queue.fal.run/";

    private final AiVideoGenRepository videoGenRepository;
    private final UserVideoGenCreditsRepository creditsRepository;
    private final VideoGenPlanService planService;
    private final OkHttpClient httpClient;

    private final String baseDir = "D:\\Backend\\videoeditor_java";
    private final String apiKey;

    public AiVideoGenService(
            AiVideoGenRepository videoGenRepository,
            UserVideoGenCreditsRepository creditsRepository,
            VideoGenPlanService planService) throws IOException {
        this.videoGenRepository = videoGenRepository;
        this.creditsRepository = creditsRepository;
        this.planService = planService;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        // Single fal.ai API key works for ALL models
        // Get yours at: https://fal.ai/dashboard/keys
        String credentialsPath = baseDir + File.separator + "credentials"
                + File.separator + "fal-api-key.txt";
        this.apiKey = Files.readString(new File(credentialsPath).toPath()).trim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * STEP 1: Submit a text-to-video generation job to fal.ai.
     * Returns immediately with a falRequestId — generation is async.
     *
     * @throws IllegalStateException if user has no plan or insufficient credits
     * @throws IllegalArgumentException if model is not accessible or params invalid
     */
    public AiVideoGen submitTextToVideo(
            User user,
            VideoGenModel model,
            String prompt,
            String negativePrompt,
            int durationSeconds,
            boolean audioEnabled,
            String aspectRatio) throws IOException {

        validateRequest(user, model, durationSeconds, prompt);
        checkFalBalance();

        int creditsNeeded = model.calculateCredits(durationSeconds, audioEnabled);
        checkAndReserveCredits(user, creditsNeeded);

        // Build fal.ai request payload
        JSONObject payload = new JSONObject();
        payload.put("prompt", prompt);
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            payload.put("negative_prompt", negativePrompt);
        }
        payload.put("duration", String.valueOf(durationSeconds));
        payload.put("aspect_ratio", aspectRatio != null ? aspectRatio : "16:9");
        payload.put("resolution", "480p");

        // Kling-specific: audio flag
        if (model.isSupportsAudio()) {
            payload.put("enable_audio", audioEnabled);
        }

        String falRequestId = submitToFalQueue(model.getTextToVideoEndpoint(), payload);

        // Persist record
        AiVideoGen record = buildRecord(user, model, AiVideoGen.GenerationType.TEXT_TO_VIDEO,
                prompt, negativePrompt, null, durationSeconds, audioEnabled,
                aspectRatio, creditsNeeded, falRequestId);
        return videoGenRepository.save(record);
    }

    /**
     * STEP 1 (variant): Submit an image-to-video job.
     * referenceImageUrl should be a public URL or base64 data URL of the uploaded image.
     */
    public AiVideoGen submitImageToVideo(
            User user,
            VideoGenModel model,
            String prompt,
            String referenceImageUrl,
            String referenceImageLocalPath,
            int durationSeconds,
            boolean audioEnabled,
            String aspectRatio) throws IOException {

        validateRequest(user, model, durationSeconds, prompt);
        checkFalBalance();

        int creditsNeeded = model.calculateCredits(durationSeconds, audioEnabled);
        checkAndReserveCredits(user, creditsNeeded);

        JSONObject payload = new JSONObject();
        payload.put("prompt", prompt);
        payload.put("image_url", referenceImageUrl);
        payload.put("duration", String.valueOf(durationSeconds));
        payload.put("aspect_ratio", aspectRatio != null ? aspectRatio : "16:9");
        if (model.isSupportsAudio()) {
            payload.put("enable_audio", audioEnabled);
        }

        String falRequestId = submitToFalQueue(model.getImageToVideoEndpoint(), payload);

        AiVideoGen record = buildRecord(user, model, AiVideoGen.GenerationType.IMAGE_TO_VIDEO,
                prompt, null, referenceImageLocalPath, durationSeconds, audioEnabled,
                aspectRatio, creditsNeeded, falRequestId);
        return videoGenRepository.save(record);
    }

    /**
     * STEP 2: Poll fal.ai for job status.
     * Call this from your /status/{falRequestId} endpoint.
     * When status is COMPLETED, automatically downloads and saves the video.
     *
     * Returns the updated AiVideoGen record.
     */
    public AiVideoGen checkAndUpdateStatus(String falRequestId) throws IOException {
        AiVideoGen record = videoGenRepository.findByFalRequestId(falRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Video job not found: " + falRequestId));

        // Skip if already terminal
        if (record.getStatus() == AiVideoGen.Status.COMPLETED
                || record.getStatus() == AiVideoGen.Status.FAILED) {
            return record;
        }

        String statusEndpoint = FAL_QUEUE_URL + record.getModel().getTextToVideoEndpoint()
                + "/requests/" + falRequestId + "/status";

        // Use image-to-video endpoint if that's the type
        if (record.getGenerationType() == AiVideoGen.GenerationType.IMAGE_TO_VIDEO) {
            statusEndpoint = FAL_QUEUE_URL + record.getModel().getImageToVideoEndpoint()
                    + "/requests/" + falRequestId + "/status";
        }

        Request statusRequest = new Request.Builder()
                .url(statusEndpoint)
                .addHeader("Authorization", "Key " + apiKey)
                .get()
                .build();

        try (Response response = httpClient.newCall(statusRequest).execute()) {
            if (!response.isSuccessful()) {
                logger.warn("fal.ai status check failed for {}: {}", falRequestId, response.code());
                return record;
            }

            String body = response.body().string();
            JSONObject json = new JSONObject(body);
            String status = json.optString("status", "IN_QUEUE");

            switch (status) {
                case "COMPLETED" -> {
                    // Fetch the result
                    String resultUrl = getResultUrl(record, falRequestId);
                    if (resultUrl != null) {
                        // Download and save video locally
                        String localPath = downloadVideo(record, resultUrl);
                        record.setVideoPath(localPath);
                        record.setVideoUrl(resultUrl);
                        record.setStatus(AiVideoGen.Status.COMPLETED);
                        record.setCompletedAt(LocalDateTime.now());
                        logger.info("Video generation completed: {}", falRequestId);
                    }
                }
                case "FAILED" -> {
                    String errorMsg = json.optString("error", "Unknown error from fal.ai");
                    record.setStatus(AiVideoGen.Status.FAILED);
                    record.setErrorMessage(errorMsg);
                    // Refund credits on failure
                    refundCredits(record.getUser(), record.getCreditsUsed());
                    logger.error("Video generation failed for {}: {}", falRequestId, errorMsg);
                }
                case "IN_PROGRESS" -> record.setStatus(AiVideoGen.Status.PROCESSING);
                // IN_QUEUE → stays PENDING
            }

            return videoGenRepository.save(record);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CREDIT MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────────

    public int getRemainingMonthlyCredits(User user) {
        int limit = planService.getMonthlyCredits(user);
        int used = getMonthlyCreditsUsed(user);
        return limit - used;
    }

    public int getRemainingDailyCredits(User user) {
        int dailyLimit = planService.getDailyCredits(user);
        UserVideoGenCredits credits = getOrCreateCreditsRecord(user);
        resetDailyIfNeeded(credits);
        return dailyLimit - credits.getTodayCreditsUsed();
    }

    public int getMonthlyCreditsUsed(User user) {
        return getOrCreateCreditsRecord(user).getCreditsUsed();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HISTORY
    // ─────────────────────────────────────────────────────────────────────────

    public List<AiVideoGen> getUserHistory(User user) {
        return videoGenRepository.findByUserOrderByCreatedAtDesc(user);
    }

    public List<AiVideoGen> getUserPendingJobs(User user) {
        return videoGenRepository.findByUserAndStatusOrderByCreatedAtDesc(
                user, AiVideoGen.Status.PENDING);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private void validateRequest(User user, VideoGenModel model, int durationSeconds, String prompt) {
        if (!planService.hasAnyVideoGenPlan(user)) {
            throw new IllegalStateException("No active AI Video Generation plan. Please purchase a plan to continue.");
        }
        if (!planService.canUseModel(user, model)) {
            throw new IllegalArgumentException(
                    "Model '" + model.getDisplayName() + "' requires a higher plan. "
                    + "Please upgrade to access this model.");
        }
        int maxDuration = planService.getMaxDurationSeconds(user);
        if (durationSeconds > maxDuration) {
            throw new IllegalArgumentException(
                    "Your plan supports a maximum of " + maxDuration + " seconds. "
                    + "Upgrade to Video Pro or Elite for 10-second videos.");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt cannot be empty.");
        }
    }

    private void checkAndReserveCredits(User user, int creditsNeeded) {
        int monthlyLimit = planService.getMonthlyCredits(user);
        int dailyLimit = planService.getDailyCredits(user);

        UserVideoGenCredits credits = getOrCreateCreditsRecord(user);
        resetDailyIfNeeded(credits);

        if (credits.getCreditsUsed() + creditsNeeded > monthlyLimit) {
            throw new IllegalStateException(
                    "Insufficient monthly credits. You need " + creditsNeeded
                    + " credits but only have " + (monthlyLimit - credits.getCreditsUsed()) + " remaining.");
        }
        if (credits.getTodayCreditsUsed() + creditsNeeded > dailyLimit) {
            throw new IllegalStateException(
                    "Daily credit limit reached. You need " + creditsNeeded
                    + " credits but only have " + (dailyLimit - credits.getTodayCreditsUsed()) + " remaining today.");
        }

        // Deduct credits immediately (before generation starts)
        credits.setCreditsUsed(credits.getCreditsUsed() + creditsNeeded);
        credits.setTodayCreditsUsed(credits.getTodayCreditsUsed() + creditsNeeded);
        creditsRepository.save(credits);
    }

    private void refundCredits(User user, int creditsToRefund) {
        try {
            UserVideoGenCredits credits = getOrCreateCreditsRecord(user);
            credits.setCreditsUsed(Math.max(0, credits.getCreditsUsed() - creditsToRefund));
            credits.setTodayCreditsUsed(Math.max(0, credits.getTodayCreditsUsed() - creditsToRefund));
            creditsRepository.save(credits);
            logger.info("Refunded {} credits to user {}", creditsToRefund, user.getId());
        } catch (Exception e) {
            logger.error("Failed to refund credits for user {}: {}", user.getId(), e.getMessage());
        }
    }

    private void checkFalBalance() throws IOException {
        Request request = new Request.Builder()
                .url("https://fal.ai/api/auth/key/info")
                .addHeader("Authorization", "Key " + apiKey)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return; // fail silently, let fal.ai reject it
            JSONObject json = new JSONObject(response.body().string());
            double balance = json.optDouble("balance", -1);
            if (balance == 0.0) {
                throw new IllegalStateException(
                        "Service temporarily unavailable. Please try again later.");
            }
        }
    }

    /**
     * Submit a job to fal.ai's async queue.
     * Returns the fal.ai request ID.
     */
    private String submitToFalQueue(String modelEndpoint, JSONObject payload) throws IOException {
        String url = FAL_QUEUE_URL + modelEndpoint;

        RequestBody body = RequestBody.create(
                payload.toString(),
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Key " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                throw new IOException("fal.ai submission failed (" + response.code() + "): " + errorBody);
            }
            String responseBody = response.body().string();
            JSONObject json = new JSONObject(responseBody);
            String requestId = json.getString("request_id");
            logger.info("Submitted to fal.ai. Request ID: {}", requestId);
            return requestId;
        }
    }

    /**
     * Once status is COMPLETED, fetch the result JSON to get the video URL.
     */
    private String getResultUrl(AiVideoGen record, String falRequestId) throws IOException {
        String endpoint = record.getGenerationType() == AiVideoGen.GenerationType.IMAGE_TO_VIDEO
                ? record.getModel().getImageToVideoEndpoint()
                : record.getModel().getTextToVideoEndpoint();

        String resultUrl = FAL_QUEUE_URL + endpoint + "/requests/" + falRequestId;

        Request request = new Request.Builder()
                .url(resultUrl)
                .addHeader("Authorization", "Key " + apiKey)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return null;
            String body = response.body().string();
            JSONObject json = new JSONObject(body);

            // fal.ai returns: { "video": { "url": "https://..." } }
            if (json.has("video")) {
                return json.getJSONObject("video").optString("url");
            }
            return null;
        }
    }

    /**
     * Download the video from fal.ai's temporary URL and save it locally.
     * Returns the relative path for serving to the frontend.
     */
    private String downloadVideo(AiVideoGen record, String videoUrl) throws IOException {
        File videoDir = new File(baseDir, "ai_video_gen" + File.separator + record.getUser().getId());
        videoDir.mkdirs();

        String fileName = "video_" + record.getFalRequestId() + ".mp4";
        File videoFile = new File(videoDir, fileName);

        Request downloadRequest = new Request.Builder()
                .url(videoUrl)
                .get()
                .build();

        try (Response response = httpClient.newCall(downloadRequest).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Failed to download video from fal.ai: " + response.code());
            }
            try (InputStream inputStream = response.body().byteStream();
                 FileOutputStream fos = new FileOutputStream(videoFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
        }

        logger.info("Downloaded video to: {}", videoFile.getAbsolutePath());
        // Return a relative path for serving via your static file controller
        return "videos/ai_video_gen/" + record.getUser().getId() + "/" + fileName;
    }

    private AiVideoGen buildRecord(User user, VideoGenModel model,
                                    AiVideoGen.GenerationType type,
                                    String prompt, String negativePrompt,
                                    String referenceImagePath,
                                    int durationSeconds, boolean audioEnabled,
                                    String aspectRatio, int creditsUsed,
                                    String falRequestId) {
        AiVideoGen record = new AiVideoGen();
        record.setUser(user);
        record.setModel(model);
        record.setGenerationType(type);
        record.setPrompt(prompt);
        record.setNegativePrompt(negativePrompt);
        record.setReferenceImagePath(referenceImagePath);
        record.setDurationSeconds(durationSeconds);
        record.setAudioEnabled(audioEnabled);
        record.setAspectRatio(aspectRatio);
        record.setCreditsUsed(creditsUsed);
        record.setFalRequestId(falRequestId);
        record.setStatus(AiVideoGen.Status.PENDING);
        record.setResolution(model.getResolution());
        return record;
    }

    private UserVideoGenCredits getOrCreateCreditsRecord(User user) {
        String month = YearMonth.now().toString();
        return creditsRepository.findByUserAndCreditMonth(user, month)
                .orElseGet(() -> {
                    UserVideoGenCredits newRecord = new UserVideoGenCredits(user, YearMonth.now());
                    newRecord.setLastResetDate(LocalDate.now().toString());
                    return creditsRepository.save(newRecord);
                });
    }

    /**
     * Resets today's credit usage if the date has changed since last access.
     * This handles the daily cap reset automatically without a cron job.
     */
    private void resetDailyIfNeeded(UserVideoGenCredits credits) {
        String today = LocalDate.now().toString();
        if (!today.equals(credits.getLastResetDate())) {
            credits.setTodayCreditsUsed(0);
            credits.setLastResetDate(today);
            creditsRepository.save(credits);
        }
    }

    public String uploadImageToFal(MultipartFile imageFile) throws IOException {
        // fal.ai accepts base64 data URIs directly in the image_url field.
        // This is the officially documented approach for non-JS/Python clients.
        String base64 = Base64.getEncoder().encodeToString(imageFile.getBytes());
        String contentType = imageFile.getContentType() != null
                ? imageFile.getContentType() : "image/jpeg";
        return "data:" + contentType + ";base64," + base64;
    }
}