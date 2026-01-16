package com.example.videoeditor.controller;

import com.example.videoeditor.entity.Payment;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.security.JwtUtil;
import com.example.videoeditor.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public PaymentController(PaymentService paymentService, JwtUtil jwtUtil, UserRepository userRepository) {
        this.paymentService = paymentService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    /**
     * Create order (called when user clicks "Upgrade to Creator/Studio")
     */
    @PostMapping("/create-order")
    public ResponseEntity<?> createOrder(
            @RequestHeader("Authorization") String token,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String planType = (String) request.get("planType"); // "CREATOR" or "STUDIO"
            Double amount = ((Number) request.get("amount")).doubleValue();
            String currency = (String) request.get("currency"); // "INR" or "USD"

            // Validate plan type
            if (!planType.equals("CREATOR") && !planType.equals("STUDIO")) {
                return ResponseEntity.badRequest().body("Invalid plan type");
            }

            Payment payment = paymentService.createOrder(user, planType, amount, currency);

            Map<String, Object> response = new HashMap<>();
            response.put("orderId", payment.getOrderId());
            response.put("amount", payment.getAmount());
            response.put("currency", payment.getCurrency());
            response.put("planType", payment.getPlanType());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error creating order: " + e.getMessage());
        }
    }

    /**
     * MOCK PAYMENT API - Simulate payment success/failure
     * In production, replace this with Razorpay/PayPal webhook
     */
    @PostMapping("/mock-payment")
    public ResponseEntity<?> mockPayment(@RequestBody Map<String, Object> request) {
        try {
            String orderId = (String) request.get("orderId");
            Boolean success = (Boolean) request.getOrDefault("success", true);

            // Simulate payment ID from gateway
            String paymentId = success ? "MOCK_PAY_" + System.currentTimeMillis() : null;

            Payment payment = paymentService.verifyAndUpgrade(orderId, paymentId, success);

            Map<String, Object> response = new HashMap<>();
            response.put("status", payment.getStatus().toString());
            response.put("orderId", payment.getOrderId());
            response.put("paymentId", payment.getPaymentId());
            response.put("planType", payment.getPlanType());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Payment verification failed: " + e.getMessage());
        }
    }

    /**
     * Verify payment status
     */
    @GetMapping("/status/{orderId}")
    public ResponseEntity<?> getPaymentStatus(
            @RequestHeader("Authorization") String token,
            @PathVariable String orderId) {
        try {
            User user = getUserFromToken(token);
            Payment payment = paymentService.getPaymentByOrderId(orderId);

            if (!payment.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(403).body("Unauthorized");
            }

            Map<String, Object> response = new HashMap<>();
            response.put("orderId", payment.getOrderId());
            response.put("status", payment.getStatus().toString());
            response.put("planType", payment.getPlanType());
            response.put("amount", payment.getAmount());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error fetching status: " + e.getMessage());
        }
    }

    private User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}