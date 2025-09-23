package com.example.videoeditor.service;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.VideoFilterUpload;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface VideoFilterUploadService {
    VideoFilterUpload uploadVideo(MultipartFile file, User user) throws Exception;
    List<VideoFilterUpload> getUserVideos(User user);
    VideoFilterUpload getVideoById(Long videoId, User user);
}
