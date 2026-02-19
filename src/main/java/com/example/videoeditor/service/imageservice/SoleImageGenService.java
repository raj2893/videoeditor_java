package com.example.videoeditor.service.imageservice;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.imageentity.SoleImageGen;
import com.example.videoeditor.entity.imageentity.UserDailyImageGenUsage;
import com.example.videoeditor.entity.imageentity.UserImageGenUsage;
import com.example.videoeditor.repository.imagerepository.SoleImageGenRepository;
import com.example.videoeditor.repository.imagerepository.UserDailyImageGenUsageRepository;
import com.example.videoeditor.repository.imagerepository.UserImageGenUsageRepository;
import com.example.videoeditor.service.PlanLimitsService;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class SoleImageGenService {

    private static final Logger logger = LoggerFactory.getLogger(SoleImageGenService.class);
    private static final String STABILITY_API_URL = "https://api.stability.ai/v1/generation/stable-diffusion-xl-1024-v1-0/text-to-image";

    private final SoleImageGenRepository soleImageGenRepository;
    private final UserImageGenUsageRepository userImageGenUsageRepository;
    private final UserDailyImageGenUsageRepository userDailyImageGenUsageRepository;
    private final PlanLimitsService planLimitsService;
    private final OkHttpClient httpClient;

    private final String baseDir = "D:\\Backend\\videoeditor_java";
    private final String apiKey;

    public SoleImageGenService(
            SoleImageGenRepository soleImageGenRepository,
            UserImageGenUsageRepository userImageGenUsageRepository,
            UserDailyImageGenUsageRepository userDailyImageGenUsageRepository,
            PlanLimitsService planLimitsService) throws IOException {
        this.soleImageGenRepository = soleImageGenRepository;
        this.userImageGenUsageRepository = userImageGenUsageRepository;
        this.userDailyImageGenUsageRepository = userDailyImageGenUsageRepository;
        this.planLimitsService = planLimitsService;
        this.httpClient = new OkHttpClient();

        // Load API key from file
        String credentialsPath = baseDir + File.separator + "credentials" + File.separator + "image-gen-api-key.txt";
        this.apiKey = Files.readString(new File(credentialsPath).toPath()).trim();
    }

    public List<SoleImageGen> generateImages(
            User user,
            String prompt,
            String negativePrompt) throws IOException {

        // Validate input
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("Prompt is required and cannot be empty");
        }

        // Get plan limits
        long dailyLimit = planLimitsService.getDailyImageGenLimit(user);
        long monthlyLimit = planLimitsService.getMonthlyImageGenLimit(user);
        int imagesPerRequest = planLimitsService.getImagesPerRequest(user);
        String resolution = planLimitsService.getImageResolution(user);
        int steps = planLimitsService.getImageSteps(user);
        double cfgScale = planLimitsService.getImageCfgScale(user);

        // Check monthly limit
        if (monthlyLimit > 0) {
            long monthlyUsage = getUserMonthlyImageGenUsage(user);
            if (monthlyUsage + 1 > monthlyLimit) {
                throw new IllegalStateException(
                        "Monthly AI Image Generation limit exceeded for plan: " + user.getRole() +
                                " (Limit: " + monthlyLimit + ", Used: " + monthlyUsage + ")"
                );
            }
        }

        // Check daily limit
        if (dailyLimit > 0) {
            long dailyUsage = getUserDailyImageGenUsage(user);
            if (dailyUsage + 1 > dailyLimit) {
                throw new IllegalStateException(
                        "Daily AI Image Generation limit exceeded for plan: " + user.getRole() +
                                " (Daily Limit: " + dailyLimit + ", Used Today: " + dailyUsage + ")"
                );
            }
        }

        int width, height;
        switch (resolution) {
            case "1024x1024":
                width = 1024; height = 1024;
                break;
            case "1152x896":
                width = 1152; height = 896;
                break;
            case "1216x832":
                width = 1216; height = 832;
                break;
            case "1344x768":
                width = 1344; height = 768;
                break;
            case "1536x640":
                width = 1536; height = 640;
                break;
            case "640x1536":
                width = 640; height = 1536;
                break;
            case "768x1344":
                width = 768; height = 1344;
                break;
            case "832x1216":
                width = 832; height = 1216;
                break;
            case "896x1152":
                width = 896; height = 1152;
                break;
            default:
                width = 1024; height = 1024;
        }

        // Build request JSON
        JSONObject requestBody = new JSONObject();
        
        JSONArray textPrompts = new JSONArray();
        JSONObject positivePrompt = new JSONObject();
        positivePrompt.put("text", prompt);
        positivePrompt.put("weight", 1);
        textPrompts.put(positivePrompt);

        if (negativePrompt != null && !negativePrompt.trim().isEmpty()) {
            JSONObject negPrompt = new JSONObject();
            negPrompt.put("text", negativePrompt);
            negPrompt.put("weight", -1);
            textPrompts.put(negPrompt);
        }

        requestBody.put("text_prompts", textPrompts);
        requestBody.put("cfg_scale", cfgScale);
        requestBody.put("height", height);
        requestBody.put("width", width);
        requestBody.put("steps", steps);
        requestBody.put("samples", imagesPerRequest);

        // Make API request
        RequestBody body = RequestBody.create(
                requestBody.toString(),
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(STABILITY_API_URL)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        List<SoleImageGen> generatedImages = new ArrayList<>();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                logger.error("Stability API error: {}", errorBody);
                throw new IOException("Stability API request failed: " + response.code() + " - " + errorBody);
            }

            String responseBody = response.body().string();
            JSONObject jsonResponse = new JSONObject(responseBody);
            JSONArray artifacts = jsonResponse.getJSONArray("artifacts");

            // Create directory for images
            File imageDir = new File(baseDir, "sole_image_gen/" + user.getId());
            imageDir.mkdirs();

            // Process each generated image
            for (int i = 0; i < artifacts.length(); i++) {
                JSONObject artifact = artifacts.getJSONObject(i);
                String base64Image = artifact.getString("base64");

                // Decode and save image
                byte[] imageBytes = Base64.getDecoder().decode(base64Image);
                String imageFileName = "img_" + System.currentTimeMillis() + "_" + i + ".png";
                File imageFile = new File(imageDir, imageFileName);

                try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                    fos.write(imageBytes);
                }

                logger.info("Saved generated image to: {}", imageFile.getAbsolutePath());

                String imagePath = "images/sole_image_gen/" + user.getId() + "/" + imageFileName;

                // Create entity
                SoleImageGen soleImageGen = new SoleImageGen();
                soleImageGen.setUser(user);
                soleImageGen.setPrompt(prompt);
                soleImageGen.setNegativePrompt(negativePrompt);
                soleImageGen.setImagePath(imagePath);
                soleImageGen.setResolution(resolution);
                soleImageGen.setSteps(steps);
                soleImageGen.setCfgScale(cfgScale);
                soleImageGen.setCreatedAt(LocalDateTime.now());

                soleImageGenRepository.save(soleImageGen);
                generatedImages.add(soleImageGen);
            }

            // Update usage (only count as 1 generation regardless of number of images)
            updateUserMonthlyImageGenUsage(user, 1);
            updateUserDailyImageGenUsage(user, 1);

            return generatedImages;
        }
    }

    public long getUserMonthlyImageGenUsage(User user) {
        YearMonth currentMonth = YearMonth.now();
        return userImageGenUsageRepository.findByUserAndMonth(user, currentMonth)
                .map(UserImageGenUsage::getGenerationsUsed)
                .orElse(0L);
    }

    private void updateUserMonthlyImageGenUsage(User user, long count) {
        YearMonth currentMonth = YearMonth.now();
        UserImageGenUsage usage = userImageGenUsageRepository.findByUserAndMonth(user, currentMonth)
                .orElseGet(() -> new UserImageGenUsage(user, currentMonth));
        usage.setGenerationsUsed(usage.getGenerationsUsed() + count);
        userImageGenUsageRepository.save(usage);
    }

    public long getUserDailyImageGenUsage(User user) {
        LocalDate today = LocalDate.now();
        return userDailyImageGenUsageRepository.findByUserAndUsageDate(user, today)
                .map(UserDailyImageGenUsage::getGenerationsUsed)
                .orElse(0L);
    }

    private void updateUserDailyImageGenUsage(User user, long count) {
        LocalDate today = LocalDate.now();
        UserDailyImageGenUsage usage = userDailyImageGenUsageRepository.findByUserAndUsageDate(user, today)
                .orElseGet(() -> new UserDailyImageGenUsage(user, today));
        usage.setGenerationsUsed(usage.getGenerationsUsed() + count);
        userDailyImageGenUsageRepository.save(usage);
    }

    public List<SoleImageGen> getUserGenerations(User user) {
        return soleImageGenRepository.findByUserOrderByCreatedAtDesc(user);
    }
}