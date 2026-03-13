package com.example.videoeditor.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_credits")
public class UserCredits {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // Single rolling pool — plan grants + topups combined
    @Column(nullable = false)
    private int balance = 0;

    // Tracks lifetime spend for analytics (never reset)
    @Column(nullable = false)
    private int totalSpent = 0;

    // When the current credit pool expires
    // Null = no credits, no expiry
    @Column
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Free-tier voice tracking (not credit-based) ───────────────────────────
    // Free users get 600 chars/month max. Resets on the 1st of each month.
    @Column(nullable = false)
    private int freeVoiceCharsUsedThisMonth = 0;

    @Column
    private String freeVoiceResetMonth; // "2026-03" format

    // Getters & setters
    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public int getBalance() { return balance; }
    public void setBalance(int balance) { this.balance = balance; }
    public int getTotalSpent() { return totalSpent; }
    public void setTotalSpent(int totalSpent) { this.totalSpent = totalSpent; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public int getFreeVoiceCharsUsedThisMonth() { return freeVoiceCharsUsedThisMonth; }
    public void setFreeVoiceCharsUsedThisMonth(int v) { this.freeVoiceCharsUsedThisMonth = v; }
    public String getFreeVoiceResetMonth() { return freeVoiceResetMonth; }
    public void setFreeVoiceResetMonth(String m) { this.freeVoiceResetMonth = m; }
}