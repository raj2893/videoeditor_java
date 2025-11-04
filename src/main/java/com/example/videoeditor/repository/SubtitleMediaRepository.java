package com.example.videoeditor.repository;

import com.example.videoeditor.entity.SubtitleMedia;
import com.example.videoeditor.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubtitleMediaRepository extends JpaRepository<SubtitleMedia, Long> {
    List<SubtitleMedia> findByUser(User user);
}