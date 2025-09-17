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

@Service
public class SoleTTSService {

    private static final Logger logger = LoggerFactory.getLogger(SoleTTSService.class);

    private final SoleTTSRepository soleTTSRepository;
    private final UserTtsUsageRepository userTtsUsageRepository;

    private final String baseDir = "D:\\Backend\\videoEditor-main";
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
            String languageCode) throws IOException, InterruptedException {
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
            SynthesisInput input = SynthesisInput.newBuilder().setText(text).build();
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
        } finally {
            // Note: Unlike the R2 version, we don't delete the audio file since it's stored locally
            // If you want to delete it after some time, implement a cleanup mechanism
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

    // Placeholder for waveform generation (reuse from VideoEditingService)
    private String generateAndSaveWaveformJson(String audioPath, Long userId) throws IOException, InterruptedException {
        String waveformFileName = "waveform_" + System.currentTimeMillis() + ".json";
        String waveformPath = "audio/sole_tts/" + userId + "/waveforms/" + waveformFileName;
        // Implementation depends on your existing waveform generation logic
        // Assume it saves the waveform JSON to baseDir + waveformPath
        File waveformDir = new File(baseDir, "audio/sole_tts/" + userId + "/waveforms");
        waveformDir.mkdirs();
        // Simulate waveform generation (replace with actual implementation)
        File waveformFile = new File(waveformDir, waveformFileName);
        // Write dummy waveform data (replace with your actual logic)
        Files.write(waveformFile.toPath(), "{}".getBytes());
        return waveformPath;
    }
}