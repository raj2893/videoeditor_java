package com.example.videoeditor.service;

import com.example.videoeditor.entity.PodcastClipMedia;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.PodcastClipMediaRepository;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.security.JwtUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PodcastClipService {

    private static final Logger logger = LoggerFactory.getLogger(PodcastClipService.class);

    private final JwtUtil jwtUtil;
    private final PodcastClipMediaRepository podcastClipMediaRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.base-dir:D:\\Backend\\videoEditor-main}")
    private String baseDir;

    @Value("${app.ffmpeg-path:C:\\Users\\raj.p\\Downloads\\ffmpeg-2025-02-17-git-b92577405b-full_build\\bin\\ffmpeg.exe}")
    private String ffmpegPath;

    @Value("${app.yt-dlp-path:C:\\Users\\raj.p\\Downloads\\yt-dlp.exe}")
    private String ytDlpPath;

    @Value("${python.path:C:\\Users\\raj.p\\AppData\\Local\\Programs\\Python\\Python311\\python.exe}")
    private String pythonPath;

    @Value("${app.whisper-script-path:D:\\Backend\\videoEditor-main\\scripts\\whisper_transcribe.py}")
    private String whisperScriptPath;

    public PodcastClipService(
            JwtUtil jwtUtil,
            PodcastClipMediaRepository podcastClipMediaRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.podcastClipMediaRepository = podcastClipMediaRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    public PodcastClipMedia uploadMedia(User user, MultipartFile mediaFile, String youtubeUrl) throws IOException {
        logger.info("Uploading media for user: {}", user.getId());

        PodcastClipMedia media = new PodcastClipMedia();
        media.setUser(user);
        media.setStatus("UPLOADED");

        String originalDirPath = baseDir + File.separator + "podcast_clips" + File.separator + user.getId() + File.separator + "original";
        File originalDir = new File(originalDirPath);
        if (!originalDir.exists() && !originalDir.mkdirs()) {
            logger.error("Failed to create original directory: {}", originalDir.getAbsolutePath());
            throw new IOException("Failed to create original directory");
        }

        if (youtubeUrl != null && !youtubeUrl.isEmpty()) {
            // Validate YouTube URL
            if (!youtubeUrl.matches("^(https?://)?(www\\.)?(youtube\\.com|youtu\\.be)/.+")) {
                logger.error("Invalid YouTube URL: {}", youtubeUrl);
                throw new IllegalArgumentException("Invalid YouTube URL");
            }
            media.setSourceUrl(youtubeUrl);
            media.setOriginalFileName("youtube_" + UUID.randomUUID().toString() + ".mp4");
            // Temp path for download later
            media.setOriginalPath("podcast_clips/" + user.getId() + "/original/" + media.getOriginalFileName());
            media.setOriginalCdnUrl("http://localhost:8080/" + media.getOriginalPath());
        } else if (mediaFile != null && !mediaFile.isEmpty()) {
            String originalFileName = mediaFile.getOriginalFilename();
            File inputFile = new File(originalDir, originalFileName);
            mediaFile.transferTo(inputFile);
            logger.debug("Saved input media to: {}", inputFile.getAbsolutePath());

            if (inputFile.length() == 0) {
                logger.error("Input file is empty: {}", inputFile.getAbsolutePath());
                throw new IOException("Input file is empty");
            }

            String originalPath = "podcast_clips/" + user.getId() + "/original/" + originalFileName;
            String originalCdnUrl = "http://localhost:8080/" + originalPath;

            media.setOriginalFileName(originalFileName);
            media.setOriginalPath(originalPath);
            media.setOriginalCdnUrl(originalCdnUrl);
        } else {
            logger.error("No file or YouTube URL provided for user: {}", user.getId());
            throw new IllegalArgumentException("Either a file or YouTube URL must be provided");
        }

        podcastClipMediaRepository.save(media);
        logger.info("Saved metadata for user: {}, media: {}", user.getId(), media.getOriginalFileName());
        return media;
    }

    public PodcastClipMedia processClips(User user, Long mediaId) throws IOException, InterruptedException {
        logger.info("Processing podcast clips for user: {}, mediaId: {}", user.getId(), mediaId);

        PodcastClipMedia media = podcastClipMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    logger.error("Media not found for id: {}", mediaId);
                    return new IllegalArgumentException("Media not found");
                });

        if (!media.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to process clips for media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to process clips for this media");
        }

        media.setStatus("PROCESSING");
        media.setProgress(0.0);
        podcastClipMediaRepository.save(media);

        // Prepare directories
        String tempDirPath = baseDir + File.separator + "podcast_clips" + File.separator + user.getId() + File.separator + "temp";
        String processedDirPath = baseDir + File.separator + "podcast_clips" + File.separator + user.getId() + File.separator + "processed";
        File tempDir = new File(tempDirPath);
        File processedDir = new File(processedDirPath);
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            logger.error("Failed to create temp directory: {}", tempDir.getAbsolutePath());
            media.setStatus("FAILED");
            podcastClipMediaRepository.save(media);
            throw new IOException("Failed to create temp directory");
        }
        if (!processedDir.exists() && !processedDir.mkdirs()) {
            logger.error("Failed to create processed directory: {}", processedDir.getAbsolutePath());
            media.setStatus("FAILED");
            podcastClipMediaRepository.save(media);
            throw new IOException("Failed to create processed directory");
        }

        File inputFile;
        if (media.getSourceUrl() != null) {
            // Download YouTube video
            String tempFileName = media.getOriginalFileName();
            String tempFilePath = tempDirPath + File.separator + tempFileName;
            inputFile = new File(tempFilePath);
            downloadYouTubeVideo(media.getSourceUrl(), tempFilePath);
        } else {
            inputFile = new File(baseDir + File.separator + media.getOriginalPath());
        }

        if (!inputFile.exists() || inputFile.length() == 0) {
            logger.error("Input file is missing or empty: {}", inputFile.getAbsolutePath());
            media.setStatus("FAILED");
            podcastClipMediaRepository.save(media);
            throw new IOException("Input file is missing or empty");
        }

        validateInputFile(inputFile);

        // Transcribe audio
        List<Map<String, Object>> segments = transcribeAudio(inputFile, mediaId);
        if (segments.isEmpty()) {
            logger.error("No transcription segments generated for mediaId: {}", mediaId);
            media.setStatus("FAILED");
            podcastClipMediaRepository.save(media);
            throw new IOException("Transcription failed");
        }

        // Score and select clips
        List<Map<String, Object>> selectedClips = selectViralClips(segments, mediaId);
        if (selectedClips.isEmpty()) {
            logger.error("No viral clips selected for mediaId: {}", mediaId);
            media.setStatus("FAILED");
            podcastClipMediaRepository.save(media);
            throw new IOException("No viral clips selected");
        }

        // Generate clips
        List<Map<String, Object>> clipMetadata = generateClips(inputFile, selectedClips, processedDirPath, user.getId(), mediaId);
        media.setClipsJson(objectMapper.writeValueAsString(clipMetadata));
        media.setStatus("SUCCESS");
        media.setProgress(100.0);
        podcastClipMediaRepository.save(media);

        // Clean up temp file if YouTube download
        if (media.getSourceUrl() != null) {
            Files.deleteIfExists(inputFile.toPath());
        }

        logger.info("Successfully processed clips for user: {}, mediaId: {}", user.getId(), mediaId);
        return media;
    }

    public List<PodcastClipMedia> getUserPodcastClipMedia(User user) {
        return podcastClipMediaRepository.findByUser(user);
    }

    public User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    logger.error("User not found for email extracted from token");
                    return new RuntimeException("User not found");
                });
    }

    private void downloadYouTubeVideo(String youtubeUrl, String outputPath) throws IOException, InterruptedException {
        List<String> command = Arrays.asList(
                ytDlpPath,
                "-f", "best[height<=720]", // Limit to 720p for speed
                youtubeUrl,
                "-o", outputPath
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                logger.debug("yt-dlp: {}", line);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            logger.error("yt-dlp failed to download video: {}", output);
            throw new IOException("Failed to download YouTube video: " + output);
        }
    }

    private void validateInputFile(File inputFile) throws IOException, InterruptedException {
        List<String> command = Arrays.asList(
                ffmpegPath.replace("ffmpeg.exe", "ffprobe.exe"),
                "-i", inputFile.getAbsolutePath(),
                "-show_streams",
                "-show_format",
                "-print_format", "json",
                "-v", "quiet"
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            logger.error("FFprobe failed to validate input file {}: {}", inputFile.getAbsolutePath(), output);
            throw new IOException("FFprobe failed to validate input file: " + output);
        }

        try {
            Map<String, Object> result = objectMapper.readValue(output.toString(), new TypeReference<Map<String, Object>>() {});
            List<?> streams = (List<?>) result.getOrDefault("streams", Collections.emptyList());
            if (streams.isEmpty()) {
                logger.error("No streams found in input file: {}", inputFile.getAbsolutePath());
                throw new IOException("No streams found in input file");
            }
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse FFprobe output for {}: {}", inputFile.getAbsolutePath(), output);
            throw new IOException("Failed to parse FFprobe output: " + output);
        }
    }

    private List<Map<String, Object>> transcribeAudio(File inputFile, Long mediaId) throws IOException, InterruptedException {
        String audioPath = inputFile.getAbsolutePath().replace(".mp4", ".mp3");
        List<String> extractAudioCommand = Arrays.asList(
            ffmpegPath,
            "-i", inputFile.getAbsolutePath(),
            "-vn",
            "-acodec", "mp3",
            "-y", audioPath
        );

        ProcessBuilder pb = new ProcessBuilder(extractAudioCommand);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            logger.error("FFmpeg failed to extract audio for {}: {}", inputFile.getAbsolutePath(), output);
            throw new IOException("FFmpeg failed to extract audio: " + output);
        }

        List<String> whisperCommand = Arrays.asList(
            pythonPath,
            whisperScriptPath,
            "--input", audioPath,
            "--output_format", "json"
        );

        pb = new ProcessBuilder(whisperCommand);
        pb.redirectErrorStream(true);
        process = pb.start();
        output = new StringBuilder();
        StringBuilder jsonOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            boolean inJson = false;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (line.trim().startsWith("[")) {
                    inJson = true; // Start of JSON array
                }
                if (inJson) {
                    jsonOutput.append(line).append("\n");
                }
            }
            // Complete JSON if it ends with a closing bracket
            if (inJson && jsonOutput.toString().trim().endsWith("]")) {
                jsonOutput = new StringBuilder(jsonOutput.toString().trim());
            }
        }
        exitCode = process.waitFor();
        Files.deleteIfExists(Paths.get(audioPath));

        if (exitCode != 0) {
            logger.error("Whisper transcription failed for mediaId {}: {}", mediaId, output);
            throw new IOException("Whisper transcription failed: " + output);
        }

        if (jsonOutput.length() == 0) {
            logger.error("No JSON output from Whisper for mediaId {}: {}", mediaId, output);
            throw new IOException("No JSON output from Whisper");
        }

        try {
            return objectMapper.readValue(jsonOutput.toString(), new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse Whisper JSON output for mediaId {}: {}", mediaId, jsonOutput);
            throw new IOException("Failed to parse Whisper JSON output: " + jsonOutput, e);
        }
    }

    private List<Map<String, Object>> selectViralClips(List<Map<String, Object>> segments, Long mediaId) {
        // Simple rule-based scoring (extendable with NLP later)
        List<Map<String, Object>> scoredClips = new ArrayList<>();
        double targetDuration = 35.0; // Average of 30-40 seconds
        int targetClipCount = 15; // Average of 10-20 clips

        // Group segments into ~35-second windows
        List<Map<String, Object>> candidateClips = new ArrayList<>();
        double currentStart = 0.0;
        StringBuilder currentText = new StringBuilder();
        double currentEnd = 0.0;
        int segmentIndex = 0;

        while (segmentIndex < segments.size()) {
            currentText.setLength(0);
            double duration = 0.0;
            int startIndex = segmentIndex;

            while (segmentIndex < segments.size() && duration < targetDuration) {
                Map<String, Object> segment = segments.get(segmentIndex);
                double start = (double) segment.get("start");
                double end = (double) segment.get("end");
                String text = (String) segment.get("text");

                if (segmentIndex == startIndex) {
                    currentStart = start;
                }
                currentEnd = end;
                currentText.append(text).append(" ");
                duration = currentEnd - currentStart;

                segmentIndex++;
                if (duration >= 30.0 && duration <= 40.0) {
                    break;
                }
            }

            if (duration >= 30.0 && duration <= 40.0) {
                double viralityScore = calculateViralityScore(currentText.toString());
                Map<String, Object> clip = new HashMap<>();
                clip.put("id", UUID.randomUUID().toString());
                clip.put("startTime", currentStart);
                clip.put("endTime", currentEnd);
                clip.put("text", currentText.toString().trim());
                clip.put("viralityScore", viralityScore);
                candidateClips.add(clip);
            }
        }

        // Sort by virality score and select top 15
        candidateClips.sort((a, b) -> Double.compare((double) b.get("viralityScore"), (double) a.get("viralityScore")));
        return candidateClips.subList(0, Math.min(targetClipCount, candidateClips.size()));
    }

    private double calculateViralityScore(String text) {
        // Simple rule-based scoring (extend with NLP later)
        double score = 0.0;
        text = text.toLowerCase();

        // Keywords for emotional/engaging content
        String[] positiveKeywords = {"amazing", "shocking", "incredible", "secret", "why", "how"};
        String[] negativeKeywords = {"worst", "fail", "disaster", "controversial"};
        for (String keyword : positiveKeywords) {
            if (text.contains(keyword)) score += 10.0;
        }
        for (String keyword : negativeKeywords) {
            if (text.contains(keyword)) score += 15.0;
        }

        // Short sentences are punchier
        int sentenceCount = text.split("[.!?]").length;
        if (sentenceCount <= 3) score += 20.0;

        // Questions drive engagement
        if (text.contains("?")) score += 15.0;

        return Math.min(score, 100.0);
    }

    private List<Map<String, Object>> generateClips(File inputFile, List<Map<String, Object>> clips, String processedDirPath, Long userId, Long mediaId) throws IOException, InterruptedException {
        List<Map<String, Object>> clipMetadata = new ArrayList<>();
        int clipIndex = 0;

        for (Map<String, Object> clip : clips) {
            String clipId = (String) clip.get("id");
            double startTime = (double) clip.get("startTime");
            double duration = (double) clip.get("endTime") - startTime;
            double viralityScore = (double) clip.get("viralityScore");
            String text = (String) clip.get("text");

            String outputFileName = "clip_" + clipId + ".mp4";
            String outputFilePath = processedDirPath + File.separator + outputFileName;

            List<String> command = Arrays.asList(
                    ffmpegPath,
                    "-i", inputFile.getAbsolutePath(),
                    "-ss", String.valueOf(startTime),
                    "-t", String.valueOf(duration),
                    "-c:v", "libx264",
                    "-c:a", "aac",
                    "-b:a", "192k",
                    "-vf", "scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2",
                    "-y", outputFilePath
            );

            executeFFmpegCommand(command, mediaId, clipIndex, duration);
            clipIndex++;

            String processedPath = "podcast_clips/" + userId + "/processed/" + outputFileName;
            String processedCdnUrl = "http://localhost:8080/" + processedPath;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("id", clipId);
            metadata.put("startTime", startTime);
            metadata.put("endTime", startTime + duration);
            metadata.put("viralityScore", viralityScore);
            metadata.put("text", text);
            metadata.put("processedPath", processedPath);
            metadata.put("processedCdnUrl", processedCdnUrl);
            clipMetadata.add(metadata);
        }

        return clipMetadata;
    }

    private void executeFFmpegCommand(List<String> command, Long mediaId, int clipIndex, double clipDuration) throws IOException, InterruptedException {
        PodcastClipMedia media = podcastClipMediaRepository.findById(mediaId)
            .orElseThrow(() -> new RuntimeException("Media not found: " + mediaId));

        List<String> updatedCommand = new ArrayList<>(command);
        updatedCommand.add("-progress");
        updatedCommand.add("pipe:");

        ProcessBuilder processBuilder = new ProcessBuilder(updatedCommand);
        processBuilder.redirectErrorStream(true);

        // Corrected temp directory path to include userId
        String tempDirPath = baseDir + File.separator + "podcast_clips" + File.separator + media.getUser().getId() + File.separator + "temp";
        File tempDir = new File(tempDirPath);
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            logger.error("Failed to create temp directory for logging: {}", tempDir.getAbsolutePath());
            media.setStatus("FAILED");
            media.setProgress(0.0);
            podcastClipMediaRepository.save(media);
            throw new IOException("Failed to create temp directory for logging");
        }

        File commandLogFile = new File(tempDir, "ffmpeg_command_" + mediaId + "_" + clipIndex + ".txt");
        try (PrintWriter writer = new PrintWriter(commandLogFile, "UTF-8")) {
            writer.println(String.join(" ", updatedCommand));
        } catch (FileNotFoundException e) {
            logger.error("Failed to write FFmpeg command log to {}: {}", commandLogFile.getAbsolutePath(), e.getMessage());
            media.setStatus("FAILED");
            media.setProgress(0.0);
            podcastClipMediaRepository.save(media);
            throw new IOException("Failed to write FFmpeg command log: " + e.getMessage(), e);
        }

        logger.debug("Executing FFmpeg command: {}", String.join(" ", updatedCommand));
        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();
        double lastProgress = -1.0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                logger.debug("FFmpeg: {}", line);
                if (line.startsWith("out_time_ms=") && !line.equals("out_time_ms=N/A")) {
                    try {
                        long outTimeUs = Long.parseLong(line.replace("out_time_ms=", ""));
                        double currentTime = outTimeUs / 1_000_000.0;
                        double totalProgress = Math.min(currentTime / clipDuration * 100.0, 100.0);

                        int roundedProgress = (int) Math.round(totalProgress);
                        if (roundedProgress != (int) lastProgress && roundedProgress >= 0 && roundedProgress <= 100 && roundedProgress % 10 == 0) {
                            media.setProgress((double) (clipIndex * 100.0 / 15 + roundedProgress / 15.0));
                            media.setStatus("PROCESSING");
                            podcastClipMediaRepository.save(media);
                            logger.info("Progress updated: {}% for mediaId: {}, clip: {}", media.getProgress(), mediaId, clipIndex);
                            lastProgress = roundedProgress;
                        }
                    } catch (NumberFormatException e) {
                        logger.error("Failed to parse out_time_ms: {}", line);
                    }
                }
            }
        }

        boolean completed = process.waitFor(10, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            media.setStatus("FAILED");
            media.setProgress(0.0);
            podcastClipMediaRepository.save(media);
            File errorLogFile = new File(tempDir, "ffmpeg_error_" + mediaId + "_" + clipIndex + ".txt");
            try (PrintWriter writer = new PrintWriter(errorLogFile, "UTF-8")) {
                writer.println(output.toString());
            } catch (FileNotFoundException e) {
                logger.error("Failed to write FFmpeg error log to {}: {}", errorLogFile.getAbsolutePath(), e.getMessage());
                throw new IOException("Failed to write FFmpeg error log: " + e.getMessage(), e);
            }
            throw new RuntimeException("FFmpeg process timed out for mediaId: " + mediaId + ", clip: " + clipIndex);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            File errorLogFile = new File(tempDir, "ffmpeg_error_" + mediaId + "_" + clipIndex + ".txt");
            try (PrintWriter writer = new PrintWriter(errorLogFile, "UTF-8")) {
                writer.println(output.toString());
            } catch (FileNotFoundException e) {
                logger.error("Failed to write FFmpeg error log to {}: {}", errorLogFile.getAbsolutePath(), e.getMessage());
                throw new IOException("Failed to write FFmpeg error log: " + e.getMessage(), e);
            }
            media.setStatus("FAILED");
            media.setProgress(0.0);
            podcastClipMediaRepository.save(media);
            throw new RuntimeException("FFmpeg process failed with exit code: " + exitCode + " for mediaId: " + mediaId + ", clip: " + clipIndex);
        }
    }
}