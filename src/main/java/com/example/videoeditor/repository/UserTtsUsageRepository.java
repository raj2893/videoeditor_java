package com.example.videoeditor.repository;

import com.example.videoeditor.entity.UserTtsUsage;
import com.example.videoeditor.entity.User;
import java.time.YearMonth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserTtsUsageRepository extends JpaRepository<UserTtsUsage, Long> {
    Optional<UserTtsUsage> findByUserAndMonth(User user, YearMonth month);
}