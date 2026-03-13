package com.example.videoeditor.enums;

/**
 * All confirmed AI Video Generation models available on fal.ai.
 *
 * VERIFIED PRICING (from fal.ai, March 2026) — $1 = ₹92.5:
 *   Wan 2.5         → $0.05/sec (480p) | $0.10/sec (720p) | $0.15/sec (1080p)
 *   Kling 2.5 Turbo → $0.07/sec flat (1080p only, no audio)
 *   Kling 2.6 Pro   → $0.07/sec (audio off) | $0.14/sec (audio on) — 1080p only
 *   Veo 3.1 Fast    → $0.10/sec (audio off) | $0.15/sec (audio on) — 720p/1080p same price
 *   Veo 3.1         → $0.20/sec (audio off) | $0.40/sec (audio on) — 720p/1080p same price
 *
 * CREDIT SYSTEM (1 credit = ₹0.50 selling price, ~₹0.17 API cost → ~3× margin on top-ups):
 *
 *   Wan 2.5  480p  5s  off  =  46 credits  (API ₹23)
 *   Wan 2.5  480p  10s off  =  92 credits  (API ₹46)
 *   Wan 2.5  720p  5s  off  =  92 credits  (API ₹46)
 *   Wan 2.5  720p  10s off  = 186 credits  (API ₹93)
 *   Wan 2.5  1080p 5s  off  = 138 credits  (API ₹69)
 *   Wan 2.5  1080p 10s off  = 278 credits  (API ₹139)
 *
 *   Kling 2.5 Turbo 1080p 5s  off =  64 credits  (API ₹32)
 *   Kling 2.5 Turbo 1080p 10s off = 130 credits  (API ₹65)
 *
 *   Kling 2.6 Pro 1080p 5s  off =  64 credits  (API ₹32)
 *   Kling 2.6 Pro 1080p 5s  on  = 130 credits  (API ₹65)
 *   Kling 2.6 Pro 1080p 10s off = 130 credits  (API ₹65)
 *   Kling 2.6 Pro 1080p 10s on  = 260 credits  (API ₹130)
 *
 *   Veo 3.1 Fast 5s  off =  92 credits  (API ₹46)
 *   Veo 3.1 Fast 5s  on  = 138 credits  (API ₹69)
 *   Veo 3.1 Fast 10s off = 186 credits  (API ₹93)
 *   Veo 3.1 Fast 10s on  = 278 credits  (API ₹139)
 *
 *   Veo 3.1 5s  off = 186 credits  (API ₹93)
 *   Veo 3.1 5s  on  = 370 credits  (API ₹185)
 *   Veo 3.1 10s off = 370 credits  (API ₹185)
 *   Veo 3.1 10s on  = 740 credits  (API ₹370)
 *
 * API KEY: Single fal.ai key covers ALL models.
 *   Get it at: https://fal.ai/dashboard → Account → API Keys
 *   Store in: credentials/fal-api-key.txt
 */
public enum VideoGenModel {

    // ── TIER 1: Starter ───────────────────────────────────────────────────────

    WAN_2_5(
            "Wan 2.5",
            "fal-ai/wan-25-preview/text-to-video",
            "fal-ai/wan-25-preview/image-to-video",
            0.05,   // $/sec at 480p — varies by resolution (see calculateCredits)
            false,  // no native audio
            "Fast & lightweight. Best for quick drafts, social content, and testing ideas."
    ),

    // ── TIER 2: Pro ───────────────────────────────────────────────────────────

    KLING_2_5_TURBO(
            "Kling 2.5 Turbo",
            "fal-ai/kling-video/v2.5-turbo/pro/text-to-video",
            "fal-ai/kling-video/v2.5-turbo/pro/image-to-video",
            0.07,   // $/sec flat — 1080p only, no audio
            false,  // no native audio
            "Cinematic motion with excellent physics and camera control. Best for product demos and action shots."
    ),

    KLING_2_6_PRO(
            "Kling 2.6 Pro",
            "fal-ai/kling-video/v2.6/pro/text-to-video",
            "fal-ai/kling-video/v2.6/pro/image-to-video",
            0.07,   // $/sec audio off — $0.14/sec audio on — 1080p only
            true,   // native audio supported
            "Cinematic video with native audio. Best for storytelling and social media."
    ),

    // ── TIER 3: Elite ─────────────────────────────────────────────────────────

    VEO_3_1_FAST(
            "Veo 3.1 Fast",
            "fal-ai/veo3.1/fast",
            "fal-ai/veo3.1/fast/image-to-video",
            0.10,   // $/sec audio off — $0.15/sec audio on — 720p/1080p same price
            true,   // native audio supported
            "Google's fast Veo model. Great quality with audio at an accessible price point."
    ),

    VEO_3_1(
            "Veo 3.1",
            "fal-ai/veo3.1",
            "fal-ai/veo3.1/image-to-video",
            0.20,   // $/sec audio off — $0.40/sec audio on — 720p/1080p same price
            true,   // native audio built-in
            "Google's flagship model. Photorealistic quality with native audio in one pass. Premium tier only."
    );

    // ─────────────────────────────────────────────────────────────────────────

    private final String displayName;
    private final String textToVideoEndpoint;
    private final String imageToVideoEndpoint;
    private final double baseCostPerSecondUsd;
    private final boolean supportsAudio;
    private final String description;

    VideoGenModel(String displayName,
                  String textToVideoEndpoint,
                  String imageToVideoEndpoint,
                  double baseCostPerSecondUsd,
                  boolean supportsAudio,
                  String description) {
        this.displayName = displayName;
        this.textToVideoEndpoint = textToVideoEndpoint;
        this.imageToVideoEndpoint = imageToVideoEndpoint;
        this.baseCostPerSecondUsd = baseCostPerSecondUsd;
        this.supportsAudio = supportsAudio;
        this.description = description;
    }

    /**
     * Returns the default resolution for this model.
     * Wan 2.5 defaults to 480p (cheapest, also used for free users).
     * All other models are fixed at 1080p.
     */
    public String getDefaultResolution() {
        return switch (this) {
            case WAN_2_5                        -> "480p";
            case KLING_2_5_TURBO, KLING_2_6_PRO -> "1080p";
            case VEO_3_1_FAST, VEO_3_1          -> "1080p";
        };
    }

    /**
     * Calculates total credits for a generation.
     *
     * Credit table (1 credit = ₹0.50 selling price, ~3× margin on top-ups):
     *
     * ┌─────────────────┬────────┬─────┬───────┬─────────┐
     * │ Model           │ Res    │ Dur │ Audio │ Credits │
     * ├─────────────────┼────────┼─────┼───────┼─────────┤
     * │ Wan 2.5         │ 480p   │  5s │  off  │    46   │
     * │ Wan 2.5         │ 480p   │ 10s │  off  │    92   │
     * │ Wan 2.5         │ 720p   │  5s │  off  │    92   │
     * │ Wan 2.5         │ 720p   │ 10s │  off  │   186   │
     * │ Wan 2.5         │ 1080p  │  5s │  off  │   138   │
     * │ Wan 2.5         │ 1080p  │ 10s │  off  │   278   │
     * ├─────────────────┼────────┼─────┼───────┼─────────┤
     * │ Kling 2.5 Turbo │ 1080p  │  5s │  off  │    64   │
     * │ Kling 2.5 Turbo │ 1080p  │ 10s │  off  │   130   │
     * ├─────────────────┼────────┼─────┼───────┼─────────┤
     * │ Kling 2.6 Pro   │ 1080p  │  5s │  off  │    64   │
     * │ Kling 2.6 Pro   │ 1080p  │  5s │  on   │   130   │
     * │ Kling 2.6 Pro   │ 1080p  │ 10s │  off  │   130   │
     * │ Kling 2.6 Pro   │ 1080p  │ 10s │  on   │   260   │
     * ├─────────────────┼────────┼─────┼───────┼─────────┤
     * │ Veo 3.1 Fast    │ any    │  5s │  off  │    92   │
     * │ Veo 3.1 Fast    │ any    │  5s │  on   │   138   │
     * │ Veo 3.1 Fast    │ any    │ 10s │  off  │   186   │
     * │ Veo 3.1 Fast    │ any    │ 10s │  on   │   278   │
     * ├─────────────────┼────────┼─────┼───────┼─────────┤
     * │ Veo 3.1         │ any    │  5s │  off  │   186   │
     * │ Veo 3.1         │ any    │  5s │  on   │   370   │
     * │ Veo 3.1         │ any    │ 10s │  off  │   370   │
     * │ Veo 3.1         │ any    │ 10s │  on   │   740   │
     * └─────────────────┴────────┴─────┴───────┴─────────┘
     *
     * @param durationSeconds duration in seconds (5 or 10)
     * @param audioOn         whether native audio is requested
     * @param resolution      "480p", "720p", or "1080p" — only affects Wan 2.5
     */
    public int calculateCredits(int durationSeconds, boolean audioOn, String resolution) {
        boolean is10s = durationSeconds > 5;
        String res = (resolution != null) ? resolution.toLowerCase() : getDefaultResolution();

        return switch (this) {

            case WAN_2_5 -> {
                // Resolution-based pricing. Wan 2.5 does not support audio.
                int base5s = switch (res) {
                    case "480p"  -> 46;
                    case "720p"  -> 92;
                    case "1080p" -> 138;
                    default      -> 92; // fallback to 720p
                };
                yield is10s ? base5s * 2 : base5s;
            }

            case KLING_2_5_TURBO -> {
                // Flat rate, 1080p only, no audio
                yield is10s ? 130 : 64;
            }

            case KLING_2_6_PRO -> {
                // Audio doubles the cost
                int base5s = audioOn ? 130 : 64;
                yield is10s ? base5s * 2 : base5s;
            }

            case VEO_3_1_FAST -> {
                // 720p and 1080p are the same price on fal.ai
                if (!is10s) yield audioOn ? 138 : 92;
                else        yield audioOn ? 278 : 186;
            }

            case VEO_3_1 -> {
                // 720p and 1080p are the same price on fal.ai
                if (!is10s) yield audioOn ? 370 : 186;
                else        yield audioOn ? 740 : 370;
            }
        };
    }

    /**
     * Convenience overload — uses model's default resolution.
     * Safe to use for Kling and Veo models where resolution is fixed.
     * For Wan 2.5, always pass resolution explicitly.
     */
    public int calculateCredits(int durationSeconds, boolean audioOn) {
        return calculateCredits(durationSeconds, audioOn, getDefaultResolution());
    }

    public String getDisplayName()          { return displayName; }
    public String getTextToVideoEndpoint()  { return textToVideoEndpoint; }
    public String getImageToVideoEndpoint() { return imageToVideoEndpoint; }
    public double getBaseCostPerSecondUsd() { return baseCostPerSecondUsd; }
    public boolean isSupportsAudio()        { return supportsAudio; }
    public String getDescription()          { return description; }
}