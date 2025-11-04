package com.example.videoeditor.repository;

import com.example.videoeditor.entity.ConvertedMedia;
import com.example.videoeditor.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConvertedMediaRepository extends JpaRepository<ConvertedMedia, Long> {
    List<ConvertedMedia> findByUser(User user);
}