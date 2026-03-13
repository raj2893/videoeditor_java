package com.example.videoeditor.service;

import com.example.videoeditor.entity.*;
import com.example.videoeditor.entity.SoleTTS.TtsProvider;
import com.example.videoeditor.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.texttospeech.v1.SynthesizeSpeechRequest;
import com.google.cloud.texttospeech.v1.SynthesizeSpeechResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

@Service
public class ExternalTtsService {

    private static final Logger logger = LoggerFactory.getLogger(ExternalTtsService.class);

    @Value("${openai.api.key}")
    private String openAiApiKey;

    @Value("${azure.tts.key}")
    private String azureApiKey;

    @Value("${azure.tts.region}")
    private String azureRegion;

    private final String baseDir = "D:\\Backend\\videoeditor_java";

    private final SoleTTSRepository soleTTSRepository;
    private final CreditService creditService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public ExternalTtsService(
            SoleTTSRepository soleTTSRepository,
            CreditService creditService) {
        this.soleTTSRepository = soleTTSRepository;
        this.creditService = creditService;
    }

    public SoleTTS generateTTS(User user, String text, String voiceId,
                               TtsProvider provider, Double speed) throws IOException, InterruptedException {

        // 1. Access check
        if (!creditService.canAccessPaidFeatures(user)) {
            throw new IllegalStateException(
                    "External AI voices require an active paid plan (Creator Lite or higher)");
        }

        // 2. Basic validation
        if (text == null || text.trim().isEmpty())
            throw new IllegalArgumentException("Text is required");
        if (voiceId == null || voiceId.trim().isEmpty())
            throw new IllegalArgumentException("Voice ID is required");

        // 3. Per-request char limit
        long maxChars = creditService.getMaxVoiceCharsPerRequest(user);
        if (text.length() > maxChars) {
            throw new IllegalArgumentException(
                    "Text exceeds limit for your plan (" + maxChars + " chars per request)");
        }

        // 4+5. Deduct from unified credit pool (1 credit per 100 chars)
        int cost = (int) Math.ceil(text.length() / 100.0);
        creditService.spend(user, cost, "External AI Voice (" + provider + "): " + text.length() + " chars");

        // 6. Route to correct provider
        byte[] audioBytes = switch (provider) {
            case OPENAI -> generateWithOpenAI(text, voiceId, speed);
            case AZURE  -> generateWithAzure(text, voiceId, speed);
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider);
        };

        // 7. Save audio file
        String prefix = provider.name().toLowerCase();
        String audioFileName = prefix + "_tts_" + System.currentTimeMillis() + ".mp3";
        File audioDir = new File(baseDir, "audio/sole_tts/" + user.getId());
        audioDir.mkdirs();
        File audioFile = new File(audioDir, audioFileName);
        try (FileOutputStream out = new FileOutputStream(audioFile)) {
            out.write(audioBytes);
        }

        String audioPath = "audio/sole_tts/" + user.getId() + "/" + audioFileName;

        // 8. Persist SoleTTS record
        SoleTTS soleTTS = new SoleTTS();
        soleTTS.setUser(user);
        soleTTS.setAudioPath(audioPath);
        soleTTS.setCreatedAt(LocalDateTime.now());
        soleTTS.setProvider(provider);
        soleTTSRepository.save(soleTTS);

        logger.info("{} TTS generated for user {}: {} chars", provider, user.getId(), text.length());
        return soleTTS;
    }

    // ─── OpenAI TTS ───────────────────────────────────────────────────────────

    private byte[] generateWithOpenAI(String text, String voiceId, Double speed)
            throws IOException, InterruptedException {
        Map<String, Object> body = new HashMap<>();
        body.put("model", "tts-1");
        body.put("input", text);
        body.put("voice", voiceId);
        if (speed != null && speed != 1.0) {
            body.put("speed", speed); // OpenAI supports 0.25–4.0 natively
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/audio/speech"))
            .header("Authorization", "Bearer " + openAiApiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new IOException("OpenAI TTS error " + response.statusCode() + ": " + new String(response.body()));
        }
        return response.body();
    }

    // ─── Azure TTS ────────────────────────────────────────────────────────────

    private byte[] generateWithAzure(String text, String voiceId, Double speed)
            throws IOException, InterruptedException {
        String rateAttr = (speed != null && speed != 1.0)
                ? String.format("%.2f", speed)
                : "1.0";

        String ssml = "<speak version='1.0' xml:lang='en-US'>" +
                "<voice name='" + voiceId + "'>" +
                "<prosody rate='" + rateAttr + "'>" + text + "</prosody>" +
                "</voice>" +
                "</speak>";

        String url = "https://" + azureRegion + ".tts.speech.microsoft.com/cognitiveservices/v1";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Ocp-Apim-Subscription-Key", azureApiKey)
            .header("Content-Type", "application/ssml+xml")
            .header("X-Microsoft-OutputFormat", "audio-16khz-128kbitrate-mono-mp3")
            .POST(HttpRequest.BodyPublishers.ofString(ssml))
            .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new IOException("Azure TTS error " + response.statusCode() + ": " + new String(response.body()));
        }
        return response.body();
    }
}