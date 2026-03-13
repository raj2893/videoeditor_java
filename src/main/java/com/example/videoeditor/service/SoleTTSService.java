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
    private final CreditService creditService;

    private final String baseDir = "D:\\Backend\\videoeditor_java";
    String credentialsPath = baseDir + File.separator + "credentials" + File.separator + "video-editor-tts-24b472478ab838d2168992684517cacfab4c11da.json";

    public SoleTTSService(
            SoleTTSRepository soleTTSRepository, CreditService creditService) {
        this.soleTTSRepository = soleTTSRepository;
        this.creditService = creditService;
    }

    public SoleTTS generateTTS(
            User user,
            String text,
            String voiceName,
            String languageCode,
            Double speed) throws IOException, InterruptedException {
        if (text == null || text.trim().isEmpty())
            throw new IllegalArgumentException("Text is required and cannot be empty");
        if (voiceName == null || voiceName.trim().isEmpty())
            throw new IllegalArgumentException("Voice name is required");
        if (languageCode == null || languageCode.trim().isEmpty())
            throw new IllegalArgumentException("Language code is required");

        // Per-request char limit (plan-based, not credit-based)
        long maxCharsPerRequest = creditService.getMaxVoiceCharsPerRequest(user);
        if (text.length() > maxCharsPerRequest) {
            throw new IllegalArgumentException(
                    "Text exceeds limit for your plan (" + maxCharsPerRequest + " chars per request)");
        }

        // Credit / free-tier gate
        if (user.getPlanType() == com.example.videoeditor.enums.PlanType.FREE) {
            creditService.checkAndRecordFreeVoice(user, text.length());
        } else {
            int cost = (int) Math.ceil(text.length() / 100.0);
            creditService.spend(user, cost, "AI Voice: " + text.length() + " characters");
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
            return soleTTS;
        }
    }

    public List<SoleTTS> getUserHistory(User user) {
        return soleTTSRepository.findByUserOrderByCreatedAtDesc(user);
    }
}