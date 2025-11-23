package com.example.videoeditor.service;

import com.example.videoeditor.entity.SoleTTS;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.UserTtsUsage;
import com.example.videoeditor.repository.SoleTTSRepository;
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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SoleTTSService {

    private static final Logger logger = LoggerFactory.getLogger(SoleTTSService.class);

    private final SoleTTSRepository soleTTSRepository;
    private final UserTtsUsageRepository userTtsUsageRepository;

    private final String baseDir = "D:\\Backend\\videoeditor_java";
    String credentialsPath = baseDir + File.separator + "credentials" + File.separator + "video-editor-tts-24b472478ab838d2168992684517cacfab4c11da.json";

    public SoleTTSService(
            SoleTTSRepository soleTTSRepository,
            UserTtsUsageRepository userTtsUsageRepository) {
        this.soleTTSRepository = soleTTSRepository;
        this.userTtsUsageRepository = userTtsUsageRepository;
    }

    public SoleTTS generateTTS(
            User user,
            String text,
            String voiceName,
            String languageCode,
            Map<String, String> ssmlConfig) throws IOException, InterruptedException {
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

        // Enforce 15-minute limit (~13,500 characters/user/month)
        if (text.length() > 13_500) {
            throw new IllegalArgumentException("Text exceeds 15-minute limit (~13,500 characters)");
        }

        // Check user TTS usage
        long userUsage = getUserTtsUsage(user);
        if (userUsage + text.length() > 13_500) {
            throw new IllegalStateException("User exceeded monthly TTS limit (13,500 characters)");
        }

        // Generate audio using Google Cloud TTS
        GoogleCredentials credentials = GoogleCredentials.fromStream(new FileInputStream(credentialsPath));
        TextToSpeechSettings settings = TextToSpeechSettings.newBuilder()
                .setCredentialsProvider(() -> credentials)
                .build();

        SoleTTS soleTTS = new SoleTTS();
        soleTTS.setUser(user);

        try (TextToSpeechClient textToSpeechClient = TextToSpeechClient.create(settings)) {
            // Build SSML text if config is provided
            String inputText = (ssmlConfig != null && !ssmlConfig.isEmpty())
                    ? buildSSMLText(text, ssmlConfig)
                    : text;

            // Use setSsml instead of setText when SSML is present
            SynthesisInput.Builder inputBuilder = SynthesisInput.newBuilder();
            if (ssmlConfig != null && !ssmlConfig.isEmpty()) {
                inputBuilder.setSsml(inputText);
            } else {
                inputBuilder.setText(inputText);
            }
            SynthesisInput input = inputBuilder.build();

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

            return soleTTS;
        }
    }

    private long getUserTtsUsage(User user) {
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
}