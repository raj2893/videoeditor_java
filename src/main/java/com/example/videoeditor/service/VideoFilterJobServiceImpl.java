package com.example.videoeditor.service;

import com.example.videoeditor.config.PresetConfig;
import com.example.videoeditor.dto.VideoFilterJobRequest;
import com.example.videoeditor.dto.VideoFilterJobResponse;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.VideoFilterJob;
import com.example.videoeditor.entity.VideoFilterUpload;
import com.example.videoeditor.repository.VideoFilterJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VideoFilterJobServiceImpl implements VideoFilterJobService {

    private final VideoFilterJobRepository repository;
    private final VideoFilterUploadService uploadService;
    private final PresetConfig presetConfig;

    @Value("${video-editor.base-path}")
    private String BASE_PATH;

    @Value("${video-editor.ffmpeg-path}")
    private String FFMPEG_PATH;

    @Override
    public VideoFilterJobResponse createJobFromUpload(Long uploadId, VideoFilterJobRequest request, User user) {
        Optional<VideoFilterJob> existingJob = repository.findByUploadedVideoIdAndStatus(uploadId, VideoFilterJob.ProcessingStatus.PENDING);
        if (existingJob.isPresent()) {
            return mapToResponse(existingJob.get()); // Return existing job
        }

        VideoFilterUpload upload = uploadService.getVideoById(uploadId, user);

        VideoFilterJob job = VideoFilterJob.builder()
            .user(user)
            .uploadedVideo(upload)
            .filterName(request.getFilterName())
            .brightness(request.getBrightness())
            .contrast(request.getContrast())
            .saturation(request.getSaturation())
            .temperature(request.getTemperature())
            .gamma(request.getGamma())
            .shadows(request.getShadows())
            .highlights(request.getHighlights())
            .vibrance(request.getVibrance())
            .hue(request.getHue())
            .exposure(request.getExposure())
            .tint(request.getTint())
            .sharpness(request.getSharpness())
            .presetName(request.getPresetName())
            .lutPath(request.getLutPath())
            .status(VideoFilterJob.ProcessingStatus.PENDING)
            .progressPercentage(0)
            .build();

        VideoFilterJob saved = repository.save(job);
        return mapToResponse(saved);
    }

    @Override
    public VideoFilterJobResponse getJob(Long jobId, User user) {
        return repository.findById(jobId)
            .filter(job -> job.getUser().getId().equals(user.getId()))
            .map(this::mapToResponse)
            .orElseThrow(() -> new RuntimeException("Job not found or not authorized"));
    }

    @Override
    public List<VideoFilterJobResponse> getJobsByUser(User user) {
        return repository.findByUserId(user.getId())
            .stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }

    @Override
    public void processJob(Long jobId, User user) {
        // Fetch the job and validate user authorization
        VideoFilterJob job = repository.findById(jobId)
            .filter(j -> j.getUser().getId().equals(user.getId()))
            .orElseThrow(() -> new RuntimeException("Job not found or unauthorized"));

        // Check if job is already processing or completed
        if (job.getStatus() == VideoFilterJob.ProcessingStatus.PROCESSING) {
            throw new RuntimeException("Job is already processing");
        }

        // Update status to PROCESSING
        job.setOutputVideoPath(null);
        job.setStatus(VideoFilterJob.ProcessingStatus.PROCESSING);
        job.setProgressPercentage(0);
        repository.save(job);

        try {
            // Prepare output path
            Path outputDir = Paths.get(BASE_PATH, String.valueOf(user.getId()), "filtered");
            Files.createDirectories(outputDir);
            String outputFileName = System.currentTimeMillis() + "_filtered.mp4";
            Path outputPath = outputDir.resolve(outputFileName);

            // Build FFmpeg command
            List<String> command = buildFFmpegCommand(job, outputPath.toString());

            // Execute FFmpeg command
            executeFFmpegCommand(command, job);

            // Update job after successful processing
            job.setOutputVideoPath(outputPath.toString());
            job.setStatus(VideoFilterJob.ProcessingStatus.COMPLETED);
            job.setProgressPercentage(100);
            repository.save(job);

        } catch (Exception e) {
            // Update job on failure
            job.setStatus(VideoFilterJob.ProcessingStatus.FAILED);
            job.setProgressPercentage(0);
            repository.save(job);
            throw new RuntimeException("Processing failed: " + e.getMessage(), e);
        }
    }

    private List<String> buildFFmpegCommand(VideoFilterJob job, String outputPath) {
        List<String> command = new ArrayList<>();
        command.add(FFMPEG_PATH);
        command.add("-i");
        command.add(job.getUploadedVideo().getFilePath());

        // Apply preset values if preset is specified - COMPLETELY OVERRIDE individual values
        if (job.getPresetName() != null && !job.getPresetName().isEmpty()) {
            Map<String, Double> presetValues = presetConfig.getPreset(job.getPresetName());
            if (presetValues != null) {
                System.out.println("Applying preset: " + job.getPresetName());
                // COMPLETELY REPLACE job values with preset values
                job.setBrightness(presetValues.get("brightness"));
                job.setContrast(presetValues.get("contrast"));
                job.setSaturation(presetValues.get("saturation"));
                job.setTemperature(presetValues.get("temperature"));
                job.setGamma(presetValues.get("gamma"));
                job.setShadows(presetValues.get("shadows"));
                job.setHighlights(presetValues.get("highlights"));
                job.setVibrance(presetValues.get("vibrance"));
                job.setHue(presetValues.get("hue"));
                job.setExposure(presetValues.get("exposure"));
                job.setTint(presetValues.get("tint"));
                job.setSharpness(presetValues.get("sharpness"));
            }
        }

        // Build filter chain - use only basic, reliable FFmpeg filters
        List<String> filters = new ArrayList<>();

        // Brightness using eq filter
        if (job.getBrightness() != null && Math.abs(job.getBrightness()) > 0.001) {
            filters.add(String.format("eq=brightness=%.3f", job.getBrightness()));
        }

        // Contrast using eq filter
        if (job.getContrast() != null && Math.abs(job.getContrast() - 1.0) > 0.001) {
            filters.add(String.format("eq=contrast=%.3f", job.getContrast()));
        }

        // Saturation using eq filter
        if (job.getSaturation() != null && Math.abs(job.getSaturation() - 1.0) > 0.001) {
            filters.add(String.format("eq=saturation=%.3f", job.getSaturation()));
        }

        // Gamma using eq filter
        if (job.getGamma() != null && Math.abs(job.getGamma() - 1.0) > 0.001) {
            filters.add(String.format("eq=gamma=%.3f", job.getGamma()));
        }

        // Hue using hue filter
        if (job.getHue() != null && Math.abs(job.getHue()) > 0.001) {
            filters.add(String.format("hue=h=%.1f", job.getHue()));
        }

        // Temperature using colorbalance (simplified)
        if (job.getTemperature() != null && Math.abs(job.getTemperature() - 6500.0) > 1.0) {
            double temp = job.getTemperature();
            if (temp < 6500) {
                // Cooler = more red
                double rs = Math.min(0.3, (6500 - temp) / 6500 * 0.3);
                filters.add(String.format("colorbalance=rs=%.3f", rs));
            } else if (temp > 6500) {
                // Warmer = more blue
                double bs = Math.min(0.3, (temp - 6500) / 3500 * 0.3);
                filters.add(String.format("colorbalance=bs=%.3f", bs));
            }
        }

        // Shadows (approximate with gamma adjustment)
        if (job.getShadows() != null && Math.abs(job.getShadows()) > 0.001) {
            double shadowGamma = 1.0 - (job.getShadows() * 0.2);
            shadowGamma = Math.max(0.5, Math.min(2.0, shadowGamma));
            filters.add(String.format("eq=gamma_r=%.3f:gamma_g=%.3f:gamma_b=%.3f", shadowGamma, shadowGamma, shadowGamma));
        }

        // Highlights (approximate with brightness adjustment)
        if (job.getHighlights() != null && Math.abs(job.getHighlights()) > 0.001) {
            double highlightBrightness = job.getHighlights() * 0.1;
            filters.add(String.format("eq=brightness=%.3f", highlightBrightness));
        }

        // Vibrance (approximate with selective saturation)
        if (job.getVibrance() != null && Math.abs(job.getVibrance()) > 0.001) {
            double vibranceSat = 1.0 + (job.getVibrance() * 0.3);
            vibranceSat = Math.max(0.0, Math.min(3.0, vibranceSat));
            filters.add(String.format("eq=saturation=%.3f", vibranceSat));
        }

        // Exposure (using eq brightness)
        if (job.getExposure() != null && Math.abs(job.getExposure()) > 0.001) {
            filters.add(String.format("eq=brightness=%.3f", job.getExposure()));
        }

        // Tint (green/magenta balance)
        if (job.getTint() != null && Math.abs(job.getTint()) > 0.001) {
            double tintBalance = job.getTint() * 0.01; // Scale down
            tintBalance = Math.max(-0.5, Math.min(0.5, tintBalance));
            filters.add(String.format("colorbalance=gs=%.3f", tintBalance));
        }

        // Sharpness
        if (job.getSharpness() != null && Math.abs(job.getSharpness()) > 0.001) {
            if (job.getSharpness() > 0) {
                // Positive sharpness
                double sharpAmount = Math.min(2.0, job.getSharpness() * 1.5);
                filters.add(String.format("unsharp=5:5:%.2f:5:5:0.0", sharpAmount));
            } else {
                // Negative sharpness (blur)
                double blurAmount = Math.min(5.0, Math.abs(job.getSharpness()));
                filters.add(String.format("boxblur=%.2f", blurAmount));
            }
        }

        // ONLY apply LUT if lutPath is explicitly provided and file exists
        if (job.getLutPath() != null && !job.getLutPath().isEmpty()) {
            Path lutFile = Paths.get(job.getLutPath());
            if (Files.exists(lutFile)) {
                filters.add(String.format("lut3d='%s'", job.getLutPath()));
            } else {
                System.out.println("Warning: LUT file not found: " + job.getLutPath());
            }
        }

        // Add filter chain to command
        if (!filters.isEmpty()) {
            command.add("-vf");
            command.add(String.join(",", filters));
        }

        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("medium");

        command.add("-profile:v");
        command.add("main");
        command.add("-level");
        command.add("3.1");

        command.add("-pix_fmt");
        command.add("yuv420p");

        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("192k");

        command.add("-movflags");
        command.add("+faststart");
        command.add("-y");
        command.add(outputPath);


        System.out.println("FFmpeg command: " + String.join(" ", command));
        System.out.println("Applied values - Brightness: " + job.getBrightness() +
            ", Contrast: " + job.getContrast() +
            ", Saturation: " + job.getSaturation());
        return command;
    }

    private void executeFFmpegCommand(List<String> command, VideoFilterJob job) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true); // Combine stdout and stderr
        Process process = processBuilder.start();

        // Optionally capture output for logging or progress tracking
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // You can parse FFmpeg output for progress if needed
                System.out.println(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("FFmpeg process failed with exit code: " + exitCode);
        }
    }

    private VideoFilterJobResponse mapToResponse(VideoFilterJob job) {
        return VideoFilterJobResponse.builder()
            .id(job.getId())
            .userId(job.getUser().getId())
            .inputVideoPath(job.getUploadedVideo().getFilePath())
            .outputVideoPath(job.getOutputVideoPath())
            .filterName(job.getFilterName())
            .brightness(job.getBrightness())
            .contrast(job.getContrast())
            .saturation(job.getSaturation())
            .temperature(job.getTemperature())
            .gamma(job.getGamma())
            .shadows(job.getShadows())
            .highlights(job.getHighlights())
            .vibrance(job.getVibrance())
            .hue(job.getHue())
            .exposure(job.getExposure())
            .tint(job.getTint())
            .sharpness(job.getSharpness())
            .presetName(job.getPresetName())
            .lutPath(job.getLutPath())
            .status(job.getStatus())
            .progressPercentage(job.getProgressPercentage())
            .build();
    }

    @Override
    public VideoFilterJobResponse updateJob(Long jobId, VideoFilterJobRequest request, User user) {
        VideoFilterJob job = repository.findById(jobId)
            .filter(j -> j.getUser().getId().equals(user.getId()))
            .orElseThrow(() -> new RuntimeException("Job not found or unauthorized"));

        // Only allow update if not already processing or completed
        if (job.getStatus() == VideoFilterJob.ProcessingStatus.PROCESSING) {
            throw new RuntimeException("Cannot update a job that is processing");
        }

        if (request.getFilterName() != null) job.setFilterName(request.getFilterName());
        if (request.getBrightness() != null) job.setBrightness(request.getBrightness());
        if (request.getContrast() != null) job.setContrast(request.getContrast());
        if (request.getSaturation() != null) job.setSaturation(request.getSaturation());
        if (request.getTemperature() != null) job.setTemperature(request.getTemperature());
        if (request.getGamma() != null) job.setGamma(request.getGamma());
        if (request.getShadows() != null) job.setShadows(request.getShadows());
        if (request.getHighlights() != null) job.setHighlights(request.getHighlights());
        if (request.getVibrance() != null) job.setVibrance(request.getVibrance());
        if (request.getHue() != null) job.setHue(request.getHue());
        if (request.getExposure() != null) job.setExposure(request.getExposure());
        if (request.getTint() != null) job.setTint(request.getTint());
        if (request.getSharpness() != null) job.setSharpness(request.getSharpness());
        if (request.getPresetName() != null) job.setPresetName(request.getPresetName());
        if (request.getLutPath() != null) job.setLutPath(request.getLutPath());

        VideoFilterJob saved = repository.save(job);
        return mapToResponse(saved);
    }

}
