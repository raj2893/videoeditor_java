package com.example.videoeditor.repository;

import com.example.videoeditor.entity.PodcastClipMedia;
import com.example.videoeditor.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PodcastClipMediaRepository extends JpaRepository<PodcastClipMedia, Long> {
    List<PodcastClipMedia> findByUser(User user);
}