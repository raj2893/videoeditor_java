package com.example.videoeditor.repository;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.UserDailyTtsUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface UserDailyTtsUsageRepository extends JpaRepository<UserDailyTtsUsage, Long> {
    Optional<UserDailyTtsUsage> findByUserAndUsageDate(User user, LocalDate usageDate);
}