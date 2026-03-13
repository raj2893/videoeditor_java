package com.example.videoeditor.service;

import com.example.videoeditor.entity.SoleTTS;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.UserDailyTtsUsage;
import com.example.videoeditor.entity.UserTtsUsage;
import com.example.videoeditor.repository.SoleTTSRepository;
import com.example.videoeditor.repository.UserDailyTtsUsageRepository;
import com.example.videoeditor.repository.UserTtsUsageRepository;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.texttospeech.v1.*;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SoleTTSService {

    private static final Logger logger = LoggerFactory.getLogger(SoleTTSService.class);

    private final SoleTTSRepository soleTTSRepository;
    private final UserTtsUsageRepository userTtsUsageRepository;
    private final UserDailyTtsUsageRepository userDailyTtsUsageRepository;
    private final PlanLimitsService planLimitsService;

    private final String baseDir = "D:\\Backend\\videoeditor_java";
    String credentialsPath = baseDir + File.separator + "credentials" + File.separator + "video-editor-tts-24b472478ab838d2168992684517cacfab4c11da.json";

    public SoleTTSService(
            SoleTTSRepository soleTTSRepository,
            UserTtsUsageRepository userTtsUsageRepository, UserDailyTtsUsageRepository userDailyTtsUsageRepository, PlanLimitsService planLimitsService) {
        this.soleTTSRepository = soleTTSRepository;
        this.userTtsUsageRepository = userTtsUsageRepository;
        this.userDailyTtsUsageRepository = userDailyTtsUsageRepository;
        this.planLimitsService = planLimitsService;
    }

    public SoleTTS generateTTS(
            User user,
            String text,
            String voiceName,
            String languageCode,
            Double speed) throws IOException, InterruptedException {
        // Validate input
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text is required and cannot be empty");
        }
        if (voiceName == null || voiceName.trim().isEmpty()) {
            throw new IllegalArgumentException("Voice name is required");
        }
        if (languageCode == null || languageCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Language code is required");
        }

        // ✅ CHANGE: Use planLimitsService instead of user.getMaxCharsPerRequest()
        long maxCharsPerRequest = planLimitsService.getMaxCharsPerRequest(user);
        if (maxCharsPerRequest > 0 && text.length() > maxCharsPerRequest) {
            throw new IllegalArgumentException(
                    "Text exceeds max characters allowed per request for " + user.getRole() +
                            " plan (Limit: " + maxCharsPerRequest + " characters per request)"
            );
        }

        // ✅ CHANGE: Use planLimitsService instead of user.getMonthlyTtsLimit()
        long monthlyLimit = planLimitsService.getMonthlyTtsLimit(user);
        if (monthlyLimit > 0) { // Only check if there's a limit (-1 means unlimited)
            long userUsage = getUserTtsUsage(user);
            if (userUsage + text.length() > monthlyLimit) {
                throw new IllegalStateException(
                        "AI Voice Generation limit exceeded for plan: " + user.getRole() +
                                " (Limit: " + monthlyLimit + ", Used: " + userUsage + ")"
                );
            }
        }

        // ✅ CHANGE: Use planLimitsService instead of user.getDailyTtsLimit()
        long dailyLimit = planLimitsService.getDailyTtsLimit(user);
        if (dailyLimit > 0) { // -1 means no daily limit
            long dailyUsage = getUserDailyTtsUsage(user);
            if (dailyUsage + text.length() > dailyLimit) {
                throw new IllegalStateException(
                        "Daily AI Voice Generation limit exceeded for plan: " + user.getRole() +
                                " (Daily Limit: " + dailyLimit + ", Used Today: " + dailyUsage + ")"
                );
            }
        }

        // Generate audio using Google Cloud TTS
        GoogleCredentials credentials = GoogleCredentials.fromStream(new FileInputStream(credentialsPath));
        TextToSpeechSettings settings = TextToSpeechSettings.newBuilder()
                .setCredentialsProvider(() -> credentials)
                .build();

        SoleTTS soleTTS = new SoleTTS();
        soleTTS.setUser(user);

        try (TextToSpeechClient textToSpeechClient = TextToSpeechClient.create(settings)) {
            SynthesisInput input;
            if (speed != null && speed != 1.0) {
                String ssml = "<speak><prosody rate=\"" + String.format("%.2f", speed) + "\">"
                        + text + "</prosody></speak>";
                input = SynthesisInput.newBuilder().setSsml(ssml).build();
            } else {
                input = SynthesisInput.newBuilder().setText(text).build();
            }

            VoiceSelectionParams voice = VoiceSelectionParams.newBuilder()
                    .setLanguageCode(languageCode)
                    .setName(voiceName)
                    .build();
            AudioConfig audioConfig = AudioConfig.newBuilder()
                    .setAudioEncoding(AudioEncoding.MP3)
                    .build();
            SynthesizeSpeechResponse response = textToSpeechClient.synthesizeSpeech(input, voice, audioConfig);
            ByteString audioContent = response.getAudioContent();

            // Save audio file locally
            String audioFileName = "tts_" + System.currentTimeMillis() + ".mp3";
            File audioDir = new File(baseDir, "audio/sole_tts/" + user.getId());
            audioDir.mkdirs();
            File audioFile = new File(audioDir, audioFileName);
            try (FileOutputStream out = new FileOutputStream(audioFile)) {
                out.write(audioContent.toByteArray());
            }
            logger.info("Saved TTS audio to file: {}", audioFile.getAbsolutePath());

            String audioPath = "audio/sole_tts/" + user.getId() + "/" + audioFileName;

            // Set SoleTTS fields
            soleTTS.setAudioPath(audioPath);
            soleTTS.setCreatedAt(LocalDateTime.now());

            // Save to database
            soleTTSRepository.save(soleTTS);

            // Update TTS usage
            updateUserTtsUsage(user, text.length());
            updateUserDailyTtsUsage(user, text.length());

            return soleTTS;
        }
    }

    public long getUserTtsUsage(User user) {
        YearMonth currentMonth = YearMonth.now();
        return userTtsUsageRepository.findByUserAndMonth(user, currentMonth)
                .map(UserTtsUsage::getCharactersUsed)
                .orElse(0L);
    }

    private void updateUserTtsUsage(User user, long characters) {
        YearMonth currentMonth = YearMonth.now();
        UserTtsUsage usage = userTtsUsageRepository.findByUserAndMonth(user, currentMonth)
                .orElseGet(() -> new UserTtsUsage(user, currentMonth));
        usage.setCharactersUsed(usage.getCharactersUsed() + characters);
        userTtsUsageRepository.save(usage);
    }

    private String buildSSMLText(String text, Map<String, String> ssmlConfig) {
        if (ssmlConfig == null || ssmlConfig.isEmpty()) {
            return text;
        }

        StringBuilder ssml = new StringBuilder("<speak>");

        // Build prosody tag attributes
        List<String> prosodyAttrs = new ArrayList<>();
        if (ssmlConfig.containsKey("rate")) {
            prosodyAttrs.add("rate=\"" + ssmlConfig.get("rate") + "\"");
        }
        if (ssmlConfig.containsKey("pitch")) {
            prosodyAttrs.add("pitch=\"" + ssmlConfig.get("pitch") + "\"");
        }
        if (ssmlConfig.containsKey("volume")) {
            prosodyAttrs.add("volume=\"" + ssmlConfig.get("volume") + "\"");
        }

        if (!prosodyAttrs.isEmpty()) {
            ssml.append("<prosody ").append(String.join(" ", prosodyAttrs)).append(">");
        }

        // Add emphasis if specified
        if (ssmlConfig.containsKey("emphasis")) {
            ssml.append("<emphasis level=\"").append(ssmlConfig.get("emphasis")).append("\">");
            ssml.append(text);
            ssml.append("</emphasis>");
        } else {
            ssml.append(text);
        }

        if (!prosodyAttrs.isEmpty()) {
            ssml.append("</prosody>");
        }

        ssml.append("</speak>");
        return ssml.toString();
    }

    public long getUserDailyTtsUsage(User user) {
        LocalDate today = LocalDate.now();
        return userDailyTtsUsageRepository.findByUserAndUsageDate(user, today)
                .map(UserDailyTtsUsage::getCharactersUsed)
                .orElse(0L);
    }

    private void updateUserDailyTtsUsage(User user, long characters) {
        LocalDate today = LocalDate.now();
        UserDailyTtsUsage usage = userDailyTtsUsageRepository.findByUserAndUsageDate(user, today)
                .orElseGet(() -> new UserDailyTtsUsage(user, today));
        usage.setCharactersUsed(usage.getCharactersUsed() + characters);
        userDailyTtsUsageRepository.save(usage);
    }

    private Map<String, String> applyEmotionPreset(String emotion, Map<String, String> customConfig) {
        Map<String, String> ssmlConfig = new HashMap<>();

        // Apply emotion preset with EXTREME values
        switch (emotion.toLowerCase()) {
            case "happy":
            case "excited":
                ssmlConfig.put("rate", "1.4");      // Much faster
                ssmlConfig.put("pitch", "+8st");    // Noticeably higher
                ssmlConfig.put("volume", "+4dB");   // Louder
                ssmlConfig.put("emphasis", "strong");
                break;
            case "calm":
            case "relaxed":
                ssmlConfig.put("rate", "0.75");     // Significantly slower
                ssmlConfig.put("pitch", "-4st");    // Lower
                ssmlConfig.put("volume", "-2dB");   // Softer
                ssmlConfig.put("emphasis", "reduced");
                break;
            case "angry":
            case "intense":
                ssmlConfig.put("rate", "1.3");      // Fast and aggressive
                ssmlConfig.put("pitch", "+6st");    // Higher tension
                ssmlConfig.put("volume", "+6dB");   // Much louder
                ssmlConfig.put("emphasis", "strong");
                break;
            case "sad":
            case "somber":
                ssmlConfig.put("rate", "0.7");      // Very slow
                ssmlConfig.put("pitch", "-6st");    // Much lower
                ssmlConfig.put("volume", "-3dB");   // Quieter
                ssmlConfig.put("emphasis", "reduced");
                break;
            case "announcer":
                ssmlConfig.put("rate", "1.0");
                ssmlConfig.put("pitch", "-2st");    // Authoritative
                ssmlConfig.put("volume", "+5dB");   // Clear and loud
                ssmlConfig.put("emphasis", "strong");
                break;
            case "meditation":
                ssmlConfig.put("rate", "0.6");      // Extremely slow
                ssmlConfig.put("pitch", "-5st");    // Deep and calming
                ssmlConfig.put("volume", "-1dB");
                ssmlConfig.put("emphasis", "reduced");
                break;
            case "enthusiastic":
                ssmlConfig.put("rate", "1.5");      // Very fast
                ssmlConfig.put("pitch", "+10st");   // Very high energy
                ssmlConfig.put("volume", "+5dB");   // Energetic volume
                ssmlConfig.put("emphasis", "strong");
                break;
            case "professional":
                ssmlConfig.put("rate", "0.95");
                ssmlConfig.put("pitch", "0st");     // Neutral
                ssmlConfig.put("volume", "+1dB");   // Clear
                // No emphasis for professional
                break;
            default:
                // Default/neutral - no modifications
                break;
        }

        // Override with custom config if provided
        if (customConfig != null && !customConfig.isEmpty()) {
            ssmlConfig.putAll(customConfig);
        }

        return ssmlConfig;
    }

    public List<SoleTTS> getUserHistory(User user) {
        return soleTTSRepository.findByUserOrderByCreatedAtDesc(user);
    }
}