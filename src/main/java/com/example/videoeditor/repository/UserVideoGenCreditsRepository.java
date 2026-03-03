package com.example.videoeditor.repository;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.UserVideoGenCredits;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserVideoGenCreditsRepository extends JpaRepository<UserVideoGenCredits, Long> {

    Optional<UserVideoGenCredits> findByUserAndCreditMonth(User user, String creditMonth);
}