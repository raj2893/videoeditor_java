package com.example.videoeditor.service;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.Video;
import com.example.videoeditor.repository.VideoRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.List;

@Service
public class VideoService {
    private final VideoRepository videoRepository;
    private final String uploadDir = "videos/";

    public VideoService(VideoRepository videoRepository) {
        this.videoRepository = videoRepository;
        new File(uploadDir).mkdirs();
    }

    public Video uploadVideo(MultipartFile file, String title, User user) throws IOException {

        String originalFilename = file.getOriginalFilename();

        // Ensure filename is unique by appending a timestamp and user ID
        String uniqueFilename = user.getId() + "_" + System.currentTimeMillis() + "_" + originalFilename;
        String filePath = uploadDir + uniqueFilename;

        file.transferTo(Paths.get(filePath));

        Video video = new Video();
        video.setTitle(title);
        video.setFilePath(uniqueFilename);
        video.setUser(user);
        return videoRepository.save(video);
    }

    public List<Video> getVideosByUser(String email) {
        return videoRepository.findByUserEmail(email);
    }

    public double getVideoDuration(String videoPath) throws IOException, InterruptedException {

        String baseDir = System.getProperty("user.dir");
        String fullPath = Paths.get(baseDir, videoPath).toString();

        ProcessBuilder builder = new ProcessBuilder(
                "C:\\Users\\raj.p\\Downloads\\ffmpeg-2025-02-17-git-b92577405b-full_build\\bin\\ffprobe.exe",
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                fullPath
        );

        System.out.println("Attempting to get duration for video at path: " + fullPath);
        File videoFile = new File(fullPath);
        if (!videoFile.exists()) {
            throw new IOException("Video file not found at path: " + fullPath);
        }


        builder.redirectErrorStream(true);

        Process process = builder.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String duration = reader.readLine();
        int exitCode = process.waitFor();

        if (exitCode != 0 || duration == null) {
            throw new IOException("Failed to get video duration");
        }
        return Double.parseDouble(duration);
    }
}
