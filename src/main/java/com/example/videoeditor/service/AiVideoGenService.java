package com.example.videoeditor.service;

import com.example.videoeditor.entity.AiVideoGen;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.enums.VideoGenModel;
import com.example.videoeditor.repository.AiVideoGenRepository;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import okhttp3.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

@Service
public class AiVideoGenService {

    private static final Logger logger = LoggerFactory.getLogger(AiVideoGenService.class);

    private static final String FAL_QUEUE_URL = "https://queue.fal.run/";

    private final AiVideoGenRepository videoGenRepository;
    private final OkHttpClient httpClient;
    private final CreditService creditService;

    private final String baseDir = "D:\\Backend\\videoeditor_java";
    private final String apiKey;

    public AiVideoGenService(
            AiVideoGenRepository videoGenRepository,
            CreditService creditService) throws IOException {
        this.videoGenRepository = videoGenRepository;
        this.creditService = creditService;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();
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
     * @param resolution "480p", "720p", or "1080p".
     *                   Only affects cost for Wan 2.5. Pass null to use model default.
     *
     * @throws IllegalStateException    if user has insufficient credits
     * @throws IllegalArgumentException if model is not accessible or params invalid
     */
    public AiVideoGen submitTextToVideo(
            User user,
            VideoGenModel model,
            String prompt,
            String negativePrompt,
            int durationSeconds,
            boolean audioEnabled,
            String aspectRatio,
            String resolution) throws IOException {

        String resolvedResolution = resolveResolution(model, resolution);
        validateRequest(user, model, durationSeconds, prompt);
        checkFalBalance();

        int creditsNeeded = model.calculateCredits(durationSeconds, audioEnabled, resolvedResolution);
        checkAndReserveCredits(user, creditsNeeded);

        JSONObject payload = new JSONObject();
        payload.put("prompt", prompt);
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            payload.put("negative_prompt", negativePrompt);
        }
        payload.put("duration", String.valueOf(durationSeconds));
        payload.put("aspect_ratio", aspectRatio != null ? aspectRatio : "16:9");
        payload.put("resolution", resolvedResolution);

        if (model.isSupportsAudio()) {
            payload.put("enable_audio", audioEnabled);
        }

        String falRequestId = submitToFalQueue(model.getTextToVideoEndpoint(), payload);

        AiVideoGen record = buildRecord(user, model, AiVideoGen.GenerationType.TEXT_TO_VIDEO,
                prompt, negativePrompt, null, durationSeconds, audioEnabled,
                aspectRatio, resolvedResolution, creditsNeeded, falRequestId);
        return videoGenRepository.save(record);
    }

    /**
     * STEP 1 (variant): Submit an image-to-video job.
     *
     * @param resolution "480p", "720p", or "1080p".
     *                   Only affects cost for Wan 2.5. Pass null to use model default.
     */
    public AiVideoGen submitImageToVideo(
            User user,
            VideoGenModel model,
            String prompt,
            String referenceImageUrl,
            String referenceImageLocalPath,
            int durationSeconds,
            boolean audioEnabled,
            String aspectRatio,
            String resolution) throws IOException {

        String resolvedResolution = resolveResolution(model, resolution);
        validateRequest(user, model, durationSeconds, prompt);
        checkFalBalance();

        int creditsNeeded = model.calculateCredits(durationSeconds, audioEnabled, resolvedResolution);
        checkAndReserveCredits(user, creditsNeeded);

        JSONObject payload = new JSONObject();
        payload.put("prompt", prompt);
        payload.put("image_url", referenceImageUrl);
        payload.put("duration", String.valueOf(durationSeconds));
        payload.put("aspect_ratio", aspectRatio != null ? aspectRatio : "16:9");
        payload.put("resolution", resolvedResolution);
        if (model.isSupportsAudio()) {
            payload.put("enable_audio", audioEnabled);
        }

        String falRequestId = submitToFalQueue(model.getImageToVideoEndpoint(), payload);

        AiVideoGen record = buildRecord(user, model, AiVideoGen.GenerationType.IMAGE_TO_VIDEO,
                prompt, null, referenceImageLocalPath, durationSeconds, audioEnabled,
                aspectRatio, resolvedResolution, creditsNeeded, falRequestId);
        return videoGenRepository.save(record);
    }

    /**
     * STEP 2: Poll fal.ai for job status.
     * When status is COMPLETED, automatically downloads and saves the video.
     * When status is FAILED, automatically refunds credits.
     */
    public AiVideoGen checkAndUpdateStatus(String falRequestId) throws IOException {
        AiVideoGen record = videoGenRepository.findByFalRequestId(falRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Video job not found: " + falRequestId));

        if (record.getStatus() == AiVideoGen.Status.COMPLETED
                || record.getStatus() == AiVideoGen.Status.FAILED) {
            return record;
        }

        String endpoint = record.getGenerationType() == AiVideoGen.GenerationType.IMAGE_TO_VIDEO
                ? record.getModel().getImageToVideoEndpoint()
                : record.getModel().getTextToVideoEndpoint();

        String statusEndpoint = FAL_QUEUE_URL + endpoint + "/requests/" + falRequestId + "/status";

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
                    String resultUrl = getResultUrl(record, falRequestId);
                    if (resultUrl != null) {
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
    // HISTORY
    // ─────────────────────────────────────────────────────────────────────────

    public List<AiVideoGen> getUserHistory(User user) {
        return videoGenRepository.findByUserOrderByCreatedAtDesc(user);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolves the resolution to use for a request.
     * - For Wan 2.5: uses the user-provided resolution, defaulting to "480p".
     * - For all other models: always uses the model's fixed default (1080p),
     *   ignoring any user-provided value since it doesn't affect API cost.
     */
    private String resolveResolution(VideoGenModel model, String requestedResolution) {
        if (model == VideoGenModel.WAN_2_5) {
            if (requestedResolution == null || requestedResolution.isBlank()) {
                return "480p"; // safe default for Wan 2.5
            }
            String res = requestedResolution.toLowerCase();
            if (!res.equals("480p") && !res.equals("720p") && !res.equals("1080p")) {
                throw new IllegalArgumentException(
                        "Invalid resolution for Wan 2.5. Must be 480p, 720p, or 1080p.");
            }
            return res;
        }
        // For Kling and Veo models, resolution is fixed — ignore user input
        return model.getDefaultResolution();
    }

    private void validateRequest(User user, VideoGenModel model, int durationSeconds, String prompt) {
        if (durationSeconds != 5 && durationSeconds != 10) {
            throw new IllegalArgumentException("Duration must be 5 or 10 seconds.");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt cannot be empty.");
        }
    }

    private void checkAndReserveCredits(User user, int creditsNeeded) {
        creditService.spend(user, creditsNeeded,
                "Video gen: " + creditsNeeded + " credits");
    }

    private void refundCredits(User user, int creditsToRefund) {
        creditService.refund(user, creditsToRefund, "Video gen failed — refund");
    }

    private void checkFalBalance() throws IOException {
        Request request = new Request.Builder()
                .url("https://fal.ai/api/auth/key/info")
                .addHeader("Authorization", "Key " + apiKey)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return;
            JSONObject json = new JSONObject(response.body().string());
            double balance = json.optDouble("balance", -1);
            if (balance == 0.0) {
                throw new IllegalStateException(
                        "Service temporarily unavailable. Please try again later.");
            }
        }
    }

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
            if (json.has("video")) {
                return json.getJSONObject("video").optString("url");
            }
            return null;
        }
    }

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
        return "videos/ai_video_gen/" + record.getUser().getId() + "/" + fileName;
    }

    private AiVideoGen buildRecord(User user, VideoGenModel model,
                                   AiVideoGen.GenerationType type,
                                   String prompt, String negativePrompt,
                                   String referenceImagePath,
                                   int durationSeconds, boolean audioEnabled,
                                   String aspectRatio, String resolution,
                                   int creditsUsed, String falRequestId) {
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
        record.setResolution(resolution);
        record.setCreditsUsed(creditsUsed);
        record.setFalRequestId(falRequestId);
        record.setStatus(AiVideoGen.Status.PENDING);
        return record;
    }

    public String uploadImageToFal(MultipartFile imageFile) throws IOException {
        String base64 = Base64.getEncoder().encodeToString(imageFile.getBytes());
        String contentType = imageFile.getContentType() != null
                ? imageFile.getContentType() : "image/jpeg";
        return "data:" + contentType + ";base64," + base64;
    }
}