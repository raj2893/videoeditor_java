package com.example.videoeditor.service;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.UserCredits;
import com.example.videoeditor.entity.UserCreditTransaction;
import com.example.videoeditor.enums.PlanType;
import com.example.videoeditor.repository.UserCreditsRepository;
import com.example.videoeditor.repository.UserCreditTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class CreditService {

    // ── Plan credit grants ────────────────────────────────────────────────────
    public static final int CREDITS_FREE         = 50;
    public static final int CREDITS_CREATOR_LITE = 300;
    public static final int CREDITS_CREATOR      = 900;
    public static final int CREDITS_STUDIO       = 2500;

    // ── Free tier voice cap ───────────────────────────────────────────────────
    public static final int FREE_VOICE_CHARS_PER_MONTH = 600;

    // ── Per-feature costs (credits) ───────────────────────────────────────────
    public static final int COST_BG_REMOVAL        = 10;
    public static final int COST_SUBTITLE_GEN      = 10;
    public static final int COST_VIDEO_SPEED       = 10;
    public static final int COST_VOICE_PER_100_CHARS = 1; // paid users only

    private final UserCreditsRepository creditsRepository;
    private final UserCreditTransactionRepository transactionRepository;

    // ── Balance query ─────────────────────────────────────────────────────────

    public int getBalance(User user) {
        if (user.isAdmin()) return Integer.MAX_VALUE;
        UserCredits credits = getOrCreate(user);
        if (isExpired(credits)) return 0;
        return Math.max(0, credits.getBalance());
    }

    public boolean hasExpired(User user) {
        UserCredits credits = getOrCreate(user);
        return isExpired(credits);
    }

    // ── Spend ─────────────────────────────────────────────────────────────────

    @Transactional
    public void spend(User user, int cost, String description) {
        if (user.isAdmin()) return; // admin never deducted
        if (cost <= 0) return;

        UserCredits credits = getOrCreate(user);

        if (isExpired(credits)) {
            throw new IllegalStateException("Your credits have expired. Please purchase a plan or topup to continue.");
        }
        if (credits.getBalance() < cost) {
            throw new IllegalStateException(
                "Insufficient credits. You need " + cost + " credits but have " + credits.getBalance() + " remaining.");
        }

        credits.setBalance(credits.getBalance() - cost);
        credits.setTotalSpent(credits.getTotalSpent() + cost);
        creditsRepository.save(credits);

        logTransaction(user, -cost, credits.getBalance(), UserCreditTransaction.Type.USAGE, description);
    }

    // ── Refund (for failed async jobs like video gen) ─────────────────────────

    @Transactional
    public void refund(User user, int amount, String description) {
        if (user.isAdmin() || amount <= 0) return;
        UserCredits credits = getOrCreate(user);
        credits.setBalance(credits.getBalance() + amount);
        creditsRepository.save(credits);
        logTransaction(user, +amount, credits.getBalance(), UserCreditTransaction.Type.REFUND, description);
    }

    // ── Plan grant (called when user buys a plan) ─────────────────────────────

    @Transactional
    public void grantPlanCredits(User user, PlanType planType) {
        int grant = creditsForPlan(planType);
        if (grant <= 0) return;

        UserCredits credits = getOrCreate(user);

        // Add to pool (unused credits carry over per Leonardo model)
        credits.setBalance(credits.getBalance() + grant);

        // Extend expiry: 30 days from now, or keep existing if it's further
        LocalDateTime newExpiry = LocalDateTime.now().plusDays(30);
        if (credits.getExpiresAt() == null || newExpiry.isAfter(credits.getExpiresAt())) {
            credits.setExpiresAt(newExpiry);
        }

        // Update user's plan type and expiry
        user.setPlanType(planType);
        user.setPlanExpiresAt(credits.getExpiresAt());

        creditsRepository.save(credits);
        logTransaction(user, +grant, credits.getBalance(),
                UserCreditTransaction.Type.PLAN_GRANT,
                planType.name() + " plan grant (" + grant + " credits)");
    }

    // ── Topup (called when user buys a credit pack) ───────────────────────────

    @Transactional
    public void addTopup(User user, int amount, String packDescription) {
        if (amount <= 0) return;

        UserCredits credits = getOrCreate(user);

        credits.setBalance(credits.getBalance() + amount);

        // Topup extends expiry by 30 days from now if that's later
        LocalDateTime newExpiry = LocalDateTime.now().plusDays(30);
        if (credits.getExpiresAt() == null || newExpiry.isAfter(credits.getExpiresAt())) {
            credits.setExpiresAt(newExpiry);
            // Keep user.planExpiresAt in sync
            user.setPlanExpiresAt(newExpiry);
        }

        creditsRepository.save(credits);
        logTransaction(user, +amount, credits.getBalance(),
                UserCreditTransaction.Type.TOPUP, packDescription);
    }

    // ── Free voice tracking (free users only, no credits) ────────────────────

    @Transactional
    public void checkAndRecordFreeVoice(User user, int charCount) {
        // Only applies to FREE plan users
        if (user.getPlanType() != PlanType.FREE) {
            throw new IllegalStateException("checkAndRecordFreeVoice called on paid user — use spend() instead");
        }

        UserCredits credits = getOrCreate(user);
        resetFreeVoiceIfNewMonth(credits);

        if (credits.getFreeVoiceCharsUsedThisMonth() + charCount > FREE_VOICE_CHARS_PER_MONTH) {
            throw new IllegalStateException(
                "Free plan voice limit reached (" + FREE_VOICE_CHARS_PER_MONTH + " characters/month). " +
                "Upgrade to a paid plan to generate more.");
        }

        credits.setFreeVoiceCharsUsedThisMonth(
                credits.getFreeVoiceCharsUsedThisMonth() + charCount);
        creditsRepository.save(credits);
    }

    public int getFreeVoiceCharsUsed(User user) {
        UserCredits credits = getOrCreate(user);
        resetFreeVoiceIfNewMonth(credits);
        return credits.getFreeVoiceCharsUsedThisMonth();
    }

    // ── Plan access helpers ───────────────────────────────────────────────────

    /**
     * All paid plans get all models — no per-model plan gating anymore.
     * Free users get no access to image/video gen.
     */
    public boolean canAccessPaidFeatures(User user) {
        if (user.isAdmin()) return true;
        return user.getPlanType() != PlanType.FREE && !hasExpired(user);
    }

    public boolean isPaid(User user) {
        return canAccessPaidFeatures(user);
    }

    // Quality/watermark limits — still plan-tier based, not credit-based
    public String getMaxVideoQuality(User user) {
        if (user.isAdmin()) return "4k";
        if (!isPaid(user))  return "720p";
        return switch (user.getPlanType()) {
            case STUDIO       -> "4k";
            case CREATOR      -> "1440p";
            case CREATOR_LITE -> "1080p";
            default           -> "720p";
        };
    }

    public boolean hasWatermark(User user) {
        return !isPaid(user);
    }

    public int getMaxVideoLengthMinutes(User user) {
        if (user.isAdmin()) return -1;
        if (!isPaid(user))  return 5;
        return switch (user.getPlanType()) {
            case STUDIO, CREATOR -> 30;
            case CREATOR_LITE    -> 10;
            default              -> 5;
        };
    }

    public long getMaxVoiceCharsPerRequest(User user) {
        if (user.isAdmin()) return 10000;
        if (!isPaid(user))  return 200;
        return switch (user.getPlanType()) {
            case STUDIO       -> 6000;
            case CREATOR      -> 4000;
            case CREATOR_LITE -> 700;
            default           -> 200;
        };
    }

    // ── Credit info for frontend ──────────────────────────────────────────────

    public int getPlanMonthlyGrant(User user) {
        return creditsForPlan(user.getPlanType());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private UserCredits getOrCreate(User user) {
        return creditsRepository.findByUser(user)
                .orElseGet(() -> {
                    UserCredits c = new UserCredits();
                    c.setUser(user);
                    c.setBalance(user.getPlanType() == PlanType.FREE ? CREDITS_FREE : 0);
                    c.setFreeVoiceResetMonth(YearMonth.now().toString());
                    return creditsRepository.save(c);
                });
    }

    private boolean isExpired(UserCredits credits) {
        if (credits.getExpiresAt() == null) return credits.getBalance() <= 0;
        return LocalDateTime.now().isAfter(credits.getExpiresAt());
    }

    private void resetFreeVoiceIfNewMonth(UserCredits credits) {
        String currentMonth = YearMonth.now().toString();
        if (!currentMonth.equals(credits.getFreeVoiceResetMonth())) {
            credits.setFreeVoiceCharsUsedThisMonth(0);
            credits.setFreeVoiceResetMonth(currentMonth);
            creditsRepository.save(credits);
        }
    }

    private int creditsForPlan(PlanType planType) {
        if (planType == null) return CREDITS_FREE;
        return switch (planType) {
            case CREATOR_LITE -> CREDITS_CREATOR_LITE;
            case CREATOR      -> CREDITS_CREATOR;
            case STUDIO       -> CREDITS_STUDIO;
            default           -> CREDITS_FREE;
        };
    }

    private void logTransaction(User user, int delta, int balanceAfter,
                                 UserCreditTransaction.Type type, String description) {
        UserCreditTransaction tx = new UserCreditTransaction();
        tx.setUser(user);
        tx.setDelta(delta);
        tx.setBalanceAfter(balanceAfter);
        tx.setType(type);
        tx.setDescription(description);
        transactionRepository.save(tx);
    }
}