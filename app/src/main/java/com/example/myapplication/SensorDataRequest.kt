package com.example.myapplication

// SensorDataRequest.kt
data class SensorDataRequest(
    val timestamp: String,
    val heartRate: Int?,
    val skinTemperature: Float?,
    val userState: String,          // "평상시" or "음주 중"
    val drinkAmount: String?,       // 음주량 (예: "2잔")
    val alcoholPercentage: Float?   // 술 도수 (예: 20.0)
)
