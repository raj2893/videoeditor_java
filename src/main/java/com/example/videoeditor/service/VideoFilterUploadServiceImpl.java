package com.example.videoeditor.service;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.VideoFilterUpload;
import com.example.videoeditor.repository.VideoFilterUploadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VideoFilterUploadServiceImpl implements VideoFilterUploadService {

    private final VideoFilterUploadRepository repository;

    @Value("${video-editor.base-path}")
    private String BASE_PATH;

    @Override
    public VideoFilterUpload uploadVideo(MultipartFile file, User user) throws Exception {
        Path userDir = Paths.get(BASE_PATH, String.valueOf(user.getId()), "original");
        Files.createDirectories(userDir);

        String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        Path filePath = userDir.resolve(fileName);
        file.transferTo(filePath.toFile());

        VideoFilterUpload upload = VideoFilterUpload.builder()
                .fileName(fileName)
                .filePath(filePath.toString())
                .user(user)
                .build();

        return repository.save(upload);
    }

    @Override
    public List<VideoFilterUpload> getUserVideos(User user) {
        return repository.findByUserId(user.getId());
    }

    @Override
    public VideoFilterUpload getVideoById(Long videoId, User user) {
        return repository.findById(videoId)
                .filter(v -> v.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new RuntimeException("Video not found or not authorized"));
    }
}
