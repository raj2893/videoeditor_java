package com.example.videoeditor.service;

import com.example.videoeditor.dto.SubtitleDTO;
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
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
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
    private final ResourceLoader resourceLoader;
    private final SubtitleService subtitleService;

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

    @Value("${app.background-image-path:classpath:assets/podcast_background.png}")
    private String backgroundImagePath;

    public PodcastClipService(
        JwtUtil jwtUtil,
        PodcastClipMediaRepository podcastClipMediaRepository,
        UserRepository userRepository,
        ObjectMapper objectMapper, ResourceLoader resourceLoader, SubtitleService subtitleService) {
        this.jwtUtil = jwtUtil;
        this.podcastClipMediaRepository = podcastClipMediaRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
      this.subtitleService = subtitleService;
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
            if (!youtubeUrl.matches("^(https?://)?(www\\.)?(youtube\\.com|youtu\\.be)/.+")) {
                logger.error("Invalid YouTube URL: {}", youtubeUrl);
                throw new IllegalArgumentException("Invalid YouTube URL");
            }
            media.setSourceUrl(youtubeUrl);
            media.setOriginalFileName("youtube_" + UUID.randomUUID().toString() + ".mp4");
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
        String processedDirPath = baseDir + File.separator + "podcast_clips" + File.separator + user.getId() + File.separator + "processed" + File.separator + "media_" + mediaId;
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

        // Validate background image exists
        File backgroundImage;
        try {
            Resource resource = resourceLoader.getResource(backgroundImagePath);
            if (!resource.exists()) {
                logger.error("Background image resource not found: {}", backgroundImagePath);
                media.setStatus("FAILED");
                podcastClipMediaRepository.save(media);
                throw new IOException("Background image not found in resources: " + backgroundImagePath);
            }

            // Copy resource to temp directory for FFmpeg to access
            backgroundImage = new File(tempDirPath + File.separator + "background.png");
            Files.copy(resource.getInputStream(), backgroundImage.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            logger.info("Extracted background image to: {}", backgroundImage.getAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to load background image: {}", e.getMessage());
            media.setStatus("FAILED");
            podcastClipMediaRepository.save(media);
            throw new IOException("Failed to load background image: " + e.getMessage(), e);
        }

        File inputFile;
        if (media.getSourceUrl() != null) {
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

        List<Map<String, Object>> segments = transcribeAudio(inputFile, mediaId);
        if (segments.isEmpty()) {
            logger.error("No transcription segments generated for mediaId: {}", mediaId);
            media.setStatus("FAILED");
            podcastClipMediaRepository.save(media);
            throw new IOException("Transcription failed");
        }

        List<Map<String, Object>> selectedClips = selectViralClips(segments, mediaId);
        if (selectedClips.isEmpty()) {
            logger.error("No viral clips selected for mediaId: {}", mediaId);
            media.setStatus("FAILED");
            podcastClipMediaRepository.save(media);
            throw new IOException("No viral clips selected");
        }

        List<Map<String, Object>> clipMetadata = generateClips(inputFile, selectedClips, processedDirPath, user.getId(), mediaId, backgroundImage, segments);
        media.setClipsJson(objectMapper.writeValueAsString(clipMetadata));
        media.setStatus("SUCCESS");
        media.setProgress(100.0);
        podcastClipMediaRepository.save(media);

        if (media.getSourceUrl() != null) {
            Files.deleteIfExists(inputFile.toPath());
        }

        logger.info("Successfully processed clips for user: {}, mediaId: {}", user.getId(), mediaId);
        Files.deleteIfExists(backgroundImage.toPath());
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
            "-f", "best[height<=720]",
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
                    inJson = true;
                }
                if (inJson) {
                    jsonOutput.append(line).append("\n");
                }
            }
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
        List<Map<String, Object>> scoredClips = new ArrayList<>();
        double targetDuration = 35.0;
        int targetClipCount = 15;

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

        candidateClips.sort((a, b) -> Double.compare((double) b.get("viralityScore"), (double) a.get("viralityScore")));
        return candidateClips.subList(0, Math.min(targetClipCount, candidateClips.size()));
    }

    private double calculateViralityScore(String text) {
        double score = 0.0;
        text = text.toLowerCase();

        String[] positiveKeywords = {"amazing", "shocking", "incredible", "secret", "why", "how"};
        String[] negativeKeywords = {"worst", "fail", "disaster", "controversial"};
        for (String keyword : positiveKeywords) {
            if (text.contains(keyword)) score += 10.0;
        }
        for (String keyword : negativeKeywords) {
            if (text.contains(keyword)) score += 15.0;
        }

        int sentenceCount = text.split("[.!?]").length;
        if (sentenceCount <= 3) score += 20.0;

        if (text.contains("?")) score += 15.0;

        return Math.min(score, 100.0);
    }

    private List<Map<String, Object>> generateClips(File inputFile, List<Map<String, Object>> clips, String processedDirPath, Long userId, Long mediaId, File backgroundImage, List<Map<String, Object>> segments) throws IOException, InterruptedException {
        List<Map<String, Object>> clipMetadata = new ArrayList<>();
        int clipIndex = 0;

        for (Map<String, Object> clip : clips) {
            String clipId = (String) clip.get("id");
            double startTime = (double) clip.get("startTime");
            double duration = (double) clip.get("endTime") - startTime;
            double viralityScore = (double) clip.get("viralityScore");
            String text = (String) clip.get("text");

            // Generate subtitles for this specific clip segment
            List<SubtitleDTO> clipSubtitles = generateSubtitlesForClip(segments, startTime, startTime + duration, mediaId, clipIndex);

            String outputFileName = "media_" + mediaId + "_clip_" + clipIndex + "_" + clipId.substring(0, 8) + ".mp4";
            String outputFilePath = processedDirPath + File.separator + outputFileName;

            // Generate clip with subtitles
            generateClipWithSubtitles(inputFile, backgroundImage, clipSubtitles, startTime, duration, outputFilePath, mediaId, clipIndex);

            clipIndex++;

            String processedPath = "podcast_clips/" + userId + "/processed/media_" + mediaId + "/" + outputFileName;
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

    private List<SubtitleDTO> generateSubtitlesForClip(List<Map<String, Object>> allSegments, double clipStart, double clipEnd, Long mediaId, int clipIndex) {
        List<SubtitleDTO> clipSubtitles = new ArrayList<>();

        for (Map<String, Object> segment : allSegments) {
            double segStart = (double) segment.get("start");
            double segEnd = (double) segment.get("end");
            String segText = (String) segment.get("text");

            // Check if segment overlaps with clip
            if (segEnd > clipStart && segStart < clipEnd) {
                // Adjust timing relative to clip start
                double relativeStart = Math.max(0, segStart - clipStart);
                double relativeEnd = Math.min(clipEnd - clipStart, segEnd - clipStart);

                if (relativeEnd > relativeStart && segText != null && !segText.trim().isEmpty()) {
                    SubtitleDTO subtitle = new SubtitleDTO();
                    subtitle.setId(UUID.randomUUID().toString());
                    subtitle.setTimelineStartTime(relativeStart);
                    subtitle.setTimelineEndTime(relativeEnd);
                    subtitle.setText(segText.trim());

                    // Podcast-optimized styling
                    subtitle.setFontFamily("Arial"); // Use Arial to avoid font warning
                    subtitle.setFontColor("#FFFFFF");
                    subtitle.setBackgroundColor("#000000");
                    subtitle.setBackgroundOpacity(0.85);
                    subtitle.setPositionX(0);
                    subtitle.setPositionY(450); // Lower position for podcast
                    subtitle.setAlignment("center");
                    subtitle.setScale(1.2); // REDUCED scale - this is key!
                    subtitle.setBackgroundH(40); // REDUCED
                    subtitle.setBackgroundW(60); // REDUCED
                    subtitle.setBackgroundBorderRadius(15);
                    subtitle.setTextBorderColor("#000000");
                    subtitle.setTextBorderWidth(2); // REDUCED
                    subtitle.setTextBorderOpacity(1.0);

                    clipSubtitles.add(subtitle);
                }
            }
        }

        logger.debug("Generated {} subtitles for clip {} ({}s to {}s)", clipSubtitles.size(), clipIndex, clipStart, clipEnd);
        return clipSubtitles;
    }

    private void generateClipWithSubtitles(File inputFile, File backgroundImage, List<SubtitleDTO> subtitles, double startTime, double duration, String outputPath, Long mediaId, int clipIndex) throws IOException, InterruptedException {
        String tempDirPath = baseDir + File.separator + "podcast_clips" + File.separator + "temp_" + mediaId;
        File tempDir = new File(tempDirPath);
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("Failed to create temp directory for subtitles");
        }

        // Step 1: Create base video with background overlay (no subtitles yet)
        String baseVideoPath = tempDir.getAbsolutePath() + File.separator + "base_" + clipIndex + ".mp4";
        List<String> baseCommand = new ArrayList<>();
        baseCommand.add(ffmpegPath);
        baseCommand.add("-loop");
        baseCommand.add("1");
        baseCommand.add("-i");
        baseCommand.add(backgroundImage.getAbsolutePath());
        baseCommand.add("-ss");
        baseCommand.add(String.valueOf(startTime));
        baseCommand.add("-t");
        baseCommand.add(String.valueOf(duration));
        baseCommand.add("-i");
        baseCommand.add(inputFile.getAbsolutePath());
        baseCommand.add("-filter_complex");
        baseCommand.add("[0:v]scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2,trim=duration=" + duration + ",setpts=PTS-STARTPTS[bg];" +
            "[1:v]setpts=PTS-STARTPTS,scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2:color=black@0[fg];" +
            "[bg][fg]overlay=(W-w)/2:(H-h)/2[vout];" +
            "[1:a]atrim=start=0:end=" + duration + ",asetpts=PTS-STARTPTS[aout]");
        baseCommand.add("-map");
        baseCommand.add("[vout]");
        baseCommand.add("-map");
        baseCommand.add("[aout]");
        baseCommand.add("-c:v");
        baseCommand.add("libx264");
        baseCommand.add("-preset");
        baseCommand.add("ultrafast"); // Fast preset for intermediate file
        baseCommand.add("-crf");
        baseCommand.add("23");
        baseCommand.add("-c:a");
        baseCommand.add("aac");
        baseCommand.add("-b:a");
        baseCommand.add("192k");
        baseCommand.add("-y");
        baseCommand.add(baseVideoPath);

        logger.debug("Creating base video without subtitles: {}", String.join(" ", baseCommand));
        executeSimpleFFmpegCommand(baseCommand);

        // Step 2: Generate and overlay subtitles in batches of 3-4 to avoid filter complexity
        if (!subtitles.isEmpty()) {
            File currentVideo = new File(baseVideoPath);
            int batchSize = 3; // Process 3 subtitles at a time

            for (int i = 0; i < subtitles.size(); i += batchSize) {
                int endIdx = Math.min(i + batchSize, subtitles.size());
                List<SubtitleDTO> batch = subtitles.subList(i, endIdx);

                String nextVideoPath = tempDir.getAbsolutePath() + File.separator + "sub_batch_" + clipIndex + "_" + i + ".mp4";
                overlaySubtitleBatch(currentVideo, batch, nextVideoPath, duration, mediaId, clipIndex, tempDir);

                // Delete previous intermediate file (except the base)
                if (!currentVideo.getAbsolutePath().equals(baseVideoPath)) {
                    Files.deleteIfExists(currentVideo.toPath());
                }

                currentVideo = new File(nextVideoPath);
            }

            // Move final result to output
            Files.move(currentVideo.toPath(), Paths.get(outputPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // Cleanup base video
            Files.deleteIfExists(Paths.get(baseVideoPath));
        } else {
            // No subtitles, just move base video to output
            Files.move(Paths.get(baseVideoPath), Paths.get(outputPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void overlaySubtitleBatch(File inputVideo, List<SubtitleDTO> subtitles, String outputPath, double duration, Long mediaId, int clipIndex, File tempDir) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-i");
        command.add(inputVideo.getAbsolutePath());

        List<File> subtitlePngs = new ArrayList<>();
        StringBuilder filterComplex = new StringBuilder();

        // Add subtitle PNG inputs
        for (int i = 0; i < subtitles.size(); i++) {
            SubtitleDTO subtitle = subtitles.get(i);

            // Generate SCALED PNG (max 1080 width to fit canvas)
            File subtitlePng = new File(tempDir, "sub_" + clipIndex + "_" + subtitle.getId() + ".png");
            subtitleService.generateTextPng(subtitle, subtitlePng, 1080, 1920);
            subtitlePngs.add(subtitlePng);

            // Scale PNG to fit if needed (max 80% of canvas width)
            command.add("-loop");
            command.add("1");
            command.add("-i");
            command.add(subtitlePng.getAbsolutePath());
        }

        // Build simple filter complex
        String lastOutput = "0:v";
        for (int i = 0; i < subtitles.size(); i++) {
            SubtitleDTO subtitle = subtitles.get(i);
            int inputIdx = i + 1; // +1 because 0 is the video

            double segStart = subtitle.getTimelineStartTime();
            double segEnd = subtitle.getTimelineEndTime();

            // Simple overlay with enable expression
            String xExpr = String.format("(W-w)/2+%d", subtitle.getPositionX() != null ? subtitle.getPositionX() : 0);
            String yExpr = String.format("(H-h)/2+%d", subtitle.getPositionY() != null ? subtitle.getPositionY() : 0);

            if (i == subtitles.size() - 1) {
                // Last subtitle - output to final
                filterComplex.append("[").append(lastOutput).append("][").append(inputIdx).append(":v]");
                filterComplex.append("overlay=x='").append(xExpr).append("':y='").append(yExpr).append("'");
                filterComplex.append(":enable='between(t,").append(String.format("%.6f", segStart)).append(",").append(String.format("%.6f", segEnd)).append(")'");
            } else {
                // Intermediate subtitle
                filterComplex.append("[").append(lastOutput).append("][").append(inputIdx).append(":v]");
                filterComplex.append("overlay=x='").append(xExpr).append("':y='").append(yExpr).append("'");
                filterComplex.append(":enable='between(t,").append(String.format("%.6f", segStart)).append(",").append(String.format("%.6f", segEnd)).append(")'");
                filterComplex.append("[tmp").append(i).append("];");
                lastOutput = "tmp" + i;
            }
        }

        command.add("-filter_complex");
        command.add(filterComplex.toString());
        command.add("-map");
        command.add("0:a");
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("ultrafast"); // Fast for intermediate
        command.add("-crf");
        command.add("23");
        command.add("-c:a");
        command.add("copy"); // Copy audio to save time
        command.add("-t");
        command.add(String.valueOf(duration));
        command.add("-y");
        command.add(outputPath);

        logger.debug("Overlaying subtitle batch: {}", String.join(" ", command));
        executeSimpleFFmpegCommand(command);

        // Cleanup PNGs
        for (File png : subtitlePngs) {
            Files.deleteIfExists(png.toPath());
        }
    }

    private void executeSimpleFFmpegCommand(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                logger.debug("FFmpeg: {}", line);
            }
        }

        boolean completed = process.waitFor(5, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            throw new RuntimeException("FFmpeg process timed out: " + output.toString());
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("FFmpeg failed with exit code " + exitCode + ": " + output.toString());
        }
    }
}