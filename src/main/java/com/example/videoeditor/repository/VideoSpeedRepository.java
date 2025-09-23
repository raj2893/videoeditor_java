package com.example.videoeditor.repository;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.VideoSpeed;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VideoSpeedRepository extends JpaRepository<VideoSpeed, Long> {
    Optional<com.example.videoeditor.entity.VideoSpeed> findByIdAndUser(Long id, User user);
    List<VideoSpeed> findByUser(User user);
}