package com.example.myapplication
// ApiService.kt
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("/api/data")
    fun sendSensorData(@Body requestData: SensorDataRequest): Call<Void>
}
