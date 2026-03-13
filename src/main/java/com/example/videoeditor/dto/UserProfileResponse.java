package com.example.videoeditor.dto;

public class UserProfileResponse {
    private Long id;
    private String email;
    private String name;
    private String picture;
    private boolean googleAuth;
    private String role;
    private int creditBalance;
    private String planType;

    public UserProfileResponse(Long id, String email, String name, String picture, boolean googleAuth, String role, int creditBalance, String planType) {
      this.id = id;
      this.email = email;
        this.name = name;
        this.picture = picture;
        this.googleAuth = googleAuth;
        this.role = role;
        this.creditBalance = creditBalance;
        this.planType = planType;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    // Getters and setters
    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPicture() {
        return picture;
    }

    public void setPicture(String picture) {
        this.picture = picture;
    }

    public boolean isGoogleAuth() {
        return googleAuth;
    }

    public void setGoogleAuth(boolean googleAuth) {
        this.googleAuth = googleAuth;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public int getCreditBalance() { return creditBalance; }
    public void setCreditBalance(int creditBalance) { this.creditBalance = creditBalance; }
    public String getPlanType() { return planType; }
    public void setPlanType(String planType) { this.planType = planType; }
}