package com.example.videoeditor.service;

import com.example.videoeditor.entity.AspectRatioMedia;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.AspectRatioMediaRepository;
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
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AspectRatioService {

    private static final Logger logger = LoggerFactory.getLogger(AspectRatioService.class);

    private final JwtUtil jwtUtil;
    private final AspectRatioMediaRepository aspectRatioMediaRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.base-dir:D:\\Backend\\videoEditor-main}")
    private String baseDir;

    @Value("${app.ffmpeg-path:C:\\Users\\raj.p\\Downloads\\ffmpeg-2025-02-17-git-b92577405b-full_build\\bin\\ffmpeg.exe}")
    private String ffmpegPath;

    public AspectRatioService(
            JwtUtil jwtUtil,
            AspectRatioMediaRepository aspectRatioMediaRepository,
            UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.aspectRatioMediaRepository = aspectRatioMediaRepository;
        this.userRepository = userRepository;
    }

    public AspectRatioMedia uploadMedia(User user, MultipartFile mediaFile) throws IOException {
        logger.info("Uploading media for user: {}", user.getId());

        if (mediaFile == null || mediaFile.isEmpty()) {
            logger.error("MultipartFile is null or empty for user: {}", user.getId());
            throw new IllegalArgumentException("Media file is null or empty");
        }

        String originalDirPath = baseDir + File.separator + "aspect_ratio" + File.separator + user.getId() + File.separator + "original";
        File originalDir = new File(originalDirPath);
        if (!originalDir.exists() && !originalDir.mkdirs()) {
            logger.error("Failed to create original directory: {}", originalDir.getAbsolutePath());
            throw new IOException("Failed to create original directory");
        }

        String originalFileName = mediaFile.getOriginalFilename();
        File inputFile = new File(originalDir, originalFileName);
        mediaFile.transferTo(inputFile);
        logger.debug("Saved input media to: {}", inputFile.getAbsolutePath());

        if (inputFile.length() == 0) {
            logger.error("Input file is empty: {}", inputFile.getAbsolutePath());
            throw new IOException("Input file is empty");
        }

        String originalPath = "aspect_ratio/" + user.getId() + "/original/" + originalFileName;
        String originalCdnUrl = "http://localhost:8080/" + originalPath;

        AspectRatioMedia aspectRatioMedia = new AspectRatioMedia();
        aspectRatioMedia.setUser(user);
        aspectRatioMedia.setOriginalFileName(originalFileName);
        aspectRatioMedia.setOriginalPath(originalPath);
        aspectRatioMedia.setOriginalCdnUrl(originalCdnUrl);
        aspectRatioMedia.setStatus("UPLOADED");
        aspectRatioMediaRepository.save(aspectRatioMedia);

        logger.info("Saved metadata for user: {}, media: {}", user.getId(), originalFileName);
        return aspectRatioMedia;
    }

    public AspectRatioMedia setAspectRatio(User user, Long mediaId, String aspectRatio) throws IOException {
        logger.info("Setting aspect ratio for user: {}, mediaId: {}", user.getId(), mediaId);

        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    logger.error("Media not found for id: {}", mediaId);
                    return new IllegalArgumentException("Media not found");
                });

        if (!media.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to set aspect ratio for media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to set aspect ratio for this media");
        }

        if (!isValidAspectRatio(aspectRatio)) {
            logger.error("Invalid aspect ratio: {}", aspectRatio);
            throw new IllegalArgumentException("Invalid aspect ratio. Must be in format 'width:height' (e.g., '16:9')");
        }

        media.setAspectRatio(aspectRatio);
        media.setStatus("CONFIGURED");
        aspectRatioMediaRepository.save(media);

        logger.info("Successfully set aspect ratio for user: {}, mediaId: {}", user.getId(), mediaId);
        return media;
    }

    public AspectRatioMedia updatePositionX(User user, Long mediaId, Integer positionX) throws IOException {
        logger.info("Updating positionX for user: {}, mediaId: {}", user.getId(), mediaId);

        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    logger.error("Media not found for id: {}", mediaId);
                    return new IllegalArgumentException("Media not found");
                });

        if (!media.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to update positionX for media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to update positionX for this media");
        }

        media.setPositionX(positionX != null ? positionX : 0);
        media.setStatus("CONFIGURED");
        aspectRatioMediaRepository.save(media);

        logger.info("Successfully updated positionX for user: {}, mediaId: {}", user.getId(), mediaId);
        return media;
    }

    public AspectRatioMedia updatePositionY(User user, Long mediaId, Integer positionY) throws IOException {
        logger.info("Updating positionY for user: {}, mediaId: {}", user.getId(), mediaId);

        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    logger.error("Media not found for id: {}", mediaId);
                    return new IllegalArgumentException("Media not found");
                });

        if (!media.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to update positionY for media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to update positionY for this media");
        }

        media.setPositionY(positionY != null ? positionY : 0);
        media.setStatus("CONFIGURED");
        aspectRatioMediaRepository.save(media);

        logger.info("Successfully updated positionY for user: {}, mediaId: {}", user.getId(), mediaId);
        return media;
    }

    public AspectRatioMedia updateScale(User user, Long mediaId, Double scale) throws IOException {
        logger.info("Updating scale for user: {}, mediaId: {}", user.getId(), mediaId);

        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    logger.error("Media not found for id: {}", mediaId);
                    return new IllegalArgumentException("Media not found");
                });

        if (!media.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to update scale for media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to update scale for this media");
        }

        if (scale != null && scale <= 0.0) {
            logger.error("Invalid scale value: {}", scale);
            throw new IllegalArgumentException("Scale must be positive");
        }

        media.setScale(scale != null ? scale : 1.0);
        media.setStatus("CONFIGURED");
        aspectRatioMediaRepository.save(media);

        logger.info("Successfully updated scale for user: {}, mediaId: {}", user.getId(), mediaId);
        return media;
    }

    public AspectRatioMedia updateOutputWidth(User user, Long mediaId, Integer width) throws IOException {
        logger.info("Updating output width for user: {}, mediaId: {}", user.getId(), mediaId);

        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
            .orElseThrow(() -> {
                logger.error("Media not found for id: {}", mediaId);
                return new IllegalArgumentException("Media not found");
            });

        if (!media.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to update output width for media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to update output width for this media");
        }

        if (width != null && width <= 0) {
            logger.error("Invalid output width: {}", width);
            throw new IllegalArgumentException("Output width must be positive");
        }

        // Ensure width is even
        if (width != null) {
            width = width % 2 == 0 ? width : width + 1;
        }

        media.setOutputWidth(width);
        media.setStatus("CONFIGURED");
        aspectRatioMediaRepository.save(media);

        logger.info("Successfully updated output width for user: {}, mediaId: {}", user.getId(), mediaId);
        return media;
    }

    public AspectRatioMedia updateOutputHeight(User user, Long mediaId, Integer height) throws IOException {
        logger.info("Updating output height for user: {}, mediaId: {}", user.getId(), mediaId);

        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
            .orElseThrow(() -> {
                logger.error("Media not found for id: {}", mediaId);
                return new IllegalArgumentException("Media not found");
            });

        if (!media.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to update output height for media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to update output height for this media");
        }

        if (height != null && height <= 0) {
            logger.error("Invalid output height: {}", height);
            throw new IllegalArgumentException("Output height must be positive");
        }

        // Ensure height is even
        if (height != null) {
            height = height % 2 == 0 ? height : height + 1;
        }

        media.setOutputHeight(height);
        media.setStatus("CONFIGURED");
        aspectRatioMediaRepository.save(media);

        logger.info("Successfully updated output height for user: {}, mediaId: {}", user.getId(), mediaId);
        return media;
    }

    // Optional: Convenience method to update both at once
    public AspectRatioMedia updateOutputResolution(User user, Long mediaId, Integer width, Integer height) throws IOException {
        logger.info("Updating output resolution for user: {}, mediaId: {}", user.getId(), mediaId);

        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
            .orElseThrow(() -> {
                logger.error("Media not found for id: {}", mediaId);
                return new IllegalArgumentException("Media not found");
            });

        if (!media.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to update output resolution for media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to update output resolution for this media");
        }

        if (width != null) {
            if (width <= 0) {
                logger.error("Invalid output width: {}", width);
                throw new IllegalArgumentException("Output width must be positive");
            }
            // Ensure width is even
            width = width % 2 == 0 ? width : width + 1;
            media.setOutputWidth(width);
        }

        if (height != null) {
            if (height <= 0) {
                logger.error("Invalid output height: {}", height);
                throw new IllegalArgumentException("Output height must be positive");
            }
            // Ensure height is even
            height = height % 2 == 0 ? height : height + 1;
            media.setOutputHeight(height);
        }

        media.setStatus("CONFIGURED");
        aspectRatioMediaRepository.save(media);

        logger.info("Successfully updated output resolution for user: {}, mediaId: {}", user.getId(), mediaId);
        return media;
    }

    public AspectRatioMedia processAspectRatio(User user, Long mediaId) throws IOException, InterruptedException {
        logger.info("Processing aspect ratio for user: {}, mediaId: {}", user.getId(), mediaId);

        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    logger.error("Media not found for id: {}", mediaId);
                    return new IllegalArgumentException("Media not found");
                });

        if (!media.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to process aspect ratio for media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to process aspect ratio for this media");
        }

        if (media.getAspectRatio() == null && (media.getOutputWidth() == null || media.getOutputHeight() == null)) {
            logger.error("Neither aspect ratio nor output resolution configured for mediaId: {}", mediaId);
            throw new IllegalStateException("Either aspect ratio or output resolution must be configured before processing");
        }

        media.setStatus("PROCESSING");
        media.setProgress(0.0);
        aspectRatioMediaRepository.save(media);

        String inputFilePath = baseDir + File.separator + media.getOriginalPath();
        File inputFile = new File(inputFilePath);
        if (!inputFile.exists() || inputFile.length() == 0) {
            logger.error("Input file is missing or empty: {}", inputFilePath);
            media.setStatus("FAILED");
            aspectRatioMediaRepository.save(media);
            throw new IOException("Input file is missing or empty");
        }

        validateInputFile(inputFile);

        String processedDirPath = baseDir + File.separator + "aspect_ratio" + File.separator + user.getId() + File.separator + "processed";
        File processedDir = new File(processedDirPath);
        if (!processedDir.exists() && !processedDir.mkdirs()) {
            logger.error("Failed to create processed directory: {}", processedDir.getAbsolutePath());
            media.setStatus("FAILED");
            aspectRatioMediaRepository.save(media);
            throw new IOException("Failed to create processed directory");
        }

        String outputFileName = "aspect_ratio_" + media.getOriginalFileName();
        String outputFilePath = processedDirPath + File.separator + outputFileName;

        Map<String, Object> videoInfo = getVideoInfo(inputFile);
        int originalWidth = (int) videoInfo.get("width");
        int originalHeight = (int) videoInfo.get("height");
        float fps = (float) videoInfo.get("fps");

        double totalDuration = getVideoDuration(inputFile);
        if (totalDuration <= 0) {
            logger.error("Invalid video duration: {}", totalDuration);
            media.setStatus("FAILED");
            aspectRatioMediaRepository.save(media);
            throw new IOException("Invalid video duration");
        }

        try {
            renderAspectRatioVideo(inputFile, new File(outputFilePath), media, originalWidth, originalHeight, fps, mediaId, totalDuration);

            String processedPath = "aspect_ratio/" + user.getId() + "/processed/" + outputFileName;
            String processedCdnUrl = "http://localhost:8080/" + processedPath;

            media.setProcessedFileName(outputFileName);
            media.setProcessedPath(processedPath);
            media.setProcessedCdnUrl(processedCdnUrl);
            media.setStatus("SUCCESS");
            media.setProgress(100.0);
            aspectRatioMediaRepository.save(media);

            logger.info("Successfully processed aspect ratio for user: {}, mediaId: {}", user.getId(), mediaId);
            return media;
        } catch (Exception e) {
            logger.error("Failed to process aspect ratio for mediaId {}: {}", mediaId, e.getMessage(), e);
            media.setStatus("FAILED");
            media.setProgress(0.0);
            aspectRatioMediaRepository.save(media);
            throw e;
        }
    }

    public List<AspectRatioMedia> getUserAspectRatioMedia(User user) {
        return aspectRatioMediaRepository.findByUser(user);
    }

    public User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    logger.error("User not found for email extracted from token");
                    return new RuntimeException("User not found");
                });
    }

    private boolean isValidAspectRatio(String aspectRatio) {
        if (aspectRatio == null) return false;
        String[] parts = aspectRatio.split(":");
        if (parts.length != 2) return false;
        try {
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);
            return width > 0 && height > 0;
        } catch (NumberFormatException e) {
            return false;
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
            logger.error("FFprobe failed to validate input file {}: {}", inputFile.getAbsolutePath(), output.toString());
            throw new IOException("FFprobe failed to validate input file: " + output.toString());
        }

        try {
            Map<String, Object> result = objectMapper.readValue(output.toString(), new TypeReference<Map<String, Object>>() {});
            List<?> streams = (List<?>) result.getOrDefault("streams", Collections.emptyList());
            if (streams.isEmpty()) {
                logger.error("No streams found in input file: {}", inputFile.getAbsolutePath());
                throw new IOException("No streams found in input file");
            }
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse FFprobe output for {}: {}", inputFile.getAbsolutePath(), output.toString());
            throw new IOException("Failed to parse FFprobe output: " + output.toString());
        }
    }

    private Map<String, Object> getVideoInfo(File inputFile) throws IOException, InterruptedException {
        List<String> command = Arrays.asList(
                ffmpegPath,
                "-i", inputFile.getAbsolutePath(),
                "-f", "null",
                "-"
        );

        ProcessBuilder pb = new ProcessBuilder(command);
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
            throw new IOException("FFmpeg failed to get video info: " + output.toString());
        }

        Map<String, Object> info = new HashMap<>();
        String outputStr = output.toString();
        Pattern resolutionPattern = Pattern.compile("Stream.*Video:.* (\\d+)x(\\d+).*?([0-9.]+) fps");
        Matcher matcher = resolutionPattern.matcher(outputStr);
        if (matcher.find()) {
            info.put("width", Integer.parseInt(matcher.group(1)));
            info.put("height", Integer.parseInt(matcher.group(2)));
            info.put("fps", Float.parseFloat(matcher.group(3)));
        } else {
            throw new IOException("Could not parse video info from FFmpeg output");
        }

        return info;
    }

    private double getVideoDuration(File videoFile) throws IOException, InterruptedException {
        List<String> command = Arrays.asList(
                ffmpegPath.replace("ffmpeg.exe", "ffprobe.exe"),
                "-i", videoFile.getAbsolutePath(),
                "-show_entries", "format=duration",
                "-v", "quiet",
                "-of", "json"
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
                logger.debug("FFprobe output: {}", line);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            logger.error("FFprobe failed to get video duration for {}: {}", videoFile.getAbsolutePath(), output.toString());
            throw new IOException("FFprobe failed to get video duration: " + output.toString());
        }

        try {
            Map<String, Object> result = objectMapper.readValue(output.toString(), new TypeReference<Map<String, Object>>() {});
            Map<String, Object> format = (Map<String, Object>) result.get("format");
            if (format == null || !format.containsKey("duration")) {
                logger.error("No duration found in FFprobe output for {}: {}", videoFile.getAbsolutePath(), output.toString());
                throw new IOException("No duration found in FFprobe output");
            }
            return Double.parseDouble(format.get("duration").toString());
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse FFprobe output for {}: {}", videoFile.getAbsolutePath(), output.toString());
            throw new IOException("Failed to parse FFprobe output: " + output.toString());
        }
    }

    private void renderAspectRatioVideo(File inputFile, File outputFile, AspectRatioMedia media, int originalWidth, int originalHeight, float fps, Long mediaId, double totalDuration) throws IOException, InterruptedException {
        double scale = media.getScale() != null ? media.getScale() : 1.0;
        int positionX = media.getPositionX() != null ? media.getPositionX() : 0;
        int positionY = media.getPositionY() != null ? media.getPositionY() : 0;

        // Use output resolution if specified, otherwise calculate from aspect ratio
        int canvasWidth, canvasHeight;

        if (media.getOutputWidth() != null && media.getOutputHeight() != null) {
            // Use explicit output resolution
            canvasWidth = media.getOutputWidth();
            canvasHeight = media.getOutputHeight();
        } else if (media.getAspectRatio() != null) {
            // Fallback to aspect ratio calculation using original dimensions
            String[] ratioParts = media.getAspectRatio().split(":");
            int targetWidth = Integer.parseInt(ratioParts[0]);
            int targetHeight = Integer.parseInt(ratioParts[1]);
            double targetAspectRatio = (double) targetWidth / targetHeight;

            if (originalWidth / (double) originalHeight > targetAspectRatio) {
                canvasWidth = originalWidth;
                canvasHeight = (int) (canvasWidth / targetAspectRatio);
            } else {
                canvasHeight = originalHeight;
                canvasWidth = (int) (canvasHeight * targetAspectRatio);
            }
        } else {
            throw new IllegalStateException("Either output resolution or aspect ratio must be specified");
        }

        // Ensure canvas dimensions are even
        canvasWidth = canvasWidth % 2 == 0 ? canvasWidth : canvasWidth + 1;
        canvasHeight = canvasHeight % 2 == 0 ? canvasHeight : canvasHeight + 1;

        // Calculate scaled dimensions
        int scaledWidth = (int) (originalWidth * scale);
        int scaledHeight = (int) (originalHeight * scale);
        // Ensure scaled dimensions are even
        scaledWidth = scaledWidth % 2 == 0 ? scaledWidth : scaledWidth + 1;
        scaledHeight = scaledHeight % 2 == 0 ? scaledHeight : scaledHeight + 1;

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-i");
        command.add(inputFile.getAbsolutePath());

        // Create a black background canvas
        StringBuilder filterComplex = new StringBuilder();
        filterComplex.append("color=c=black:s=").append(canvasWidth).append("x").append(canvasHeight)
            .append(":d=").append(String.format("%.6f", totalDuration)).append("[base];");

        // Scale the input video to exact dimensions (allows overflow/cropping)
        filterComplex.append("[0:v]scale=");
        filterComplex.append(String.format("%d:%d", scaledWidth, scaledHeight));
        filterComplex.append(":flags=lanczos[scaled];");

        // Apply overlay with centered positioning adjusted by positionX and positionY
        // Parts exceeding canvas boundaries will be automatically cropped
        String xExpr = String.format("(W/2)+(%d)-(w/2)", positionX);
        String yExpr = String.format("(H/2)+(%d)-(h/2)", positionY);
        filterComplex.append("[base][scaled]overlay=x='").append(xExpr)
            .append("':y='").append(yExpr)
            .append("':format=auto[vout]");

        command.add("-filter_complex");
        command.add(filterComplex.toString());

        command.add("-map");
        command.add("0:a");
        command.add("-map");
        command.add("[vout]");
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("medium");
        command.add("-crf");
        command.add("23");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("192k");
        command.add("-r");
        command.add(String.format("%.2f", fps));
        command.add("-y");
        command.add(outputFile.getAbsolutePath());

        executeFFmpegCommand(command, mediaId, totalDuration);
    }

    private void executeFFmpegCommand(List<String> command, Long mediaId, double totalDuration) throws IOException, InterruptedException {
        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
            .orElseThrow(() -> new RuntimeException("Media not found: " + mediaId));

        List<String> updatedCommand = new ArrayList<>(command);
        updatedCommand.add("-progress");
        updatedCommand.add("pipe:");

        ProcessBuilder processBuilder = new ProcessBuilder(updatedCommand);
        processBuilder.redirectErrorStream(true);

        // Ensure the temp directory exists
        String tempDirPath = baseDir + File.separator + "aspect_ratio" + File.separator + "temp";
        File tempDir = new File(tempDirPath);
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            logger.error("Failed to create temp directory: {}", tempDir.getAbsolutePath());
            media.setStatus("FAILED");
            media.setProgress(0.0);
            aspectRatioMediaRepository.save(media);
            throw new IOException("Failed to create temp directory");
        }

        File commandLogFile = new File(tempDir, "ffmpeg_command_" + mediaId + ".txt");
        try (PrintWriter writer = new PrintWriter(commandLogFile, "UTF-8")) {
            writer.println(String.join(" ", updatedCommand));
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
                        double totalProgress = Math.min(currentTime / totalDuration * 100.0, 100.0);

                        int roundedProgress = (int) Math.round(totalProgress);
                        if (roundedProgress != (int) lastProgress && roundedProgress >= 0 && roundedProgress <= 100 && roundedProgress % 10 == 0) {
                            media.setProgress((double) roundedProgress);
                            media.setStatus("PROCESSING");
                            aspectRatioMediaRepository.save(media);
                            logger.info("Progress updated: {}% for mediaId: {}", roundedProgress, mediaId);
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
            aspectRatioMediaRepository.save(media);
            File errorLogFile = new File(tempDir, "ffmpeg_error_" + mediaId + ".txt");
            try (PrintWriter writer = new PrintWriter(errorLogFile, "UTF-8")) {
                writer.println(output.toString());
            }
            throw new RuntimeException("FFmpeg process timed out after 10 minutes for mediaId: " + mediaId + ". Output logged to: " + errorLogFile.getAbsolutePath());
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            File errorLogFile = new File(tempDir, "ffmpeg_error_" + mediaId + ".txt");
            try (PrintWriter writer = new PrintWriter(errorLogFile, "UTF-8")) {
                writer.println(output.toString());
            }
            media.setStatus("FAILED");
            media.setProgress(0.0);
            aspectRatioMediaRepository.save(media);
            throw new RuntimeException("FFmpeg process failed with exit code: " + exitCode + " for mediaId: " + mediaId + ". Output logged to: " + errorLogFile.getAbsolutePath());
        }
    }
}