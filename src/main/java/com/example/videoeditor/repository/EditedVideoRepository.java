package com.example.videoeditor.repository;

import com.example.videoeditor.entity.EditedVideo;
import com.example.videoeditor.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EditedVideoRepository extends JpaRepository<EditedVideo, Long> {
    List<EditedVideo> findByUser(User user);
}

