package com.example.videoeditor.repository.imagerepository;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.imageentity.UserDailyImageGenUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface UserDailyImageGenUsageRepository extends JpaRepository<UserDailyImageGenUsage, Long> {
    Optional<UserDailyImageGenUsage> findByUserAndUsageDate(User user, LocalDate usageDate);
}