package com.example.videoeditor.repository;

import com.example.videoeditor.entity.CompressedMedia;
import com.example.videoeditor.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompressedMediaRepository extends JpaRepository<CompressedMedia, Long> {
    List<CompressedMedia> findByUser(User user);
}