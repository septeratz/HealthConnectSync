package com.example.myapplication

// SensorDataRequest.kt
data class SensorDataRequest(
    val timestamp: String,
    val heart_rate: Int?,
    val user_state: String,          // "평상시" or "음주 중"
    val drink_amount: String?,       // 음주량 (예: "2잔")
    val alcohol_percentage: Float?   // 술 도수 (예: 20.0)
)
