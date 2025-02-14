package com.example.myapplication

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.core.Single
import android.util.Log
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import java.util.concurrent.TimeUnit

private const val BASE_URL = "https://moh7cm1z80.execute-api.us-east-1.amazonaws.com/prod/"

object RetrofitClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    val instance: SensorDataService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())  // ✅ JSON 변환기
            .addCallAdapterFactory(RxJava3CallAdapterFactory.create())  // ✅ RxJava3 Call Adapter 추가
            .build()
            .create(SensorDataService::class.java)
    }
}

// ✅ API 서비스 인터페이스
interface SensorDataService {
    @POST("sensor_data")
    @Headers("Content-Type: application/json")
    fun sendSensorData(@Body sensorData: SensorDataRequest): Single<Response<Unit>>  // ✅ Void → Unit 변경
}


// ✅ 데이터 전송 함수
fun sendDataToAWS(
    timestamp: String,
    heartRate: Int?,
    userState: String,
    drinkAmount: Int?,
    alcoholPercentage: Float?
) {
    val sensorData = SensorDataRequest(
        timestamp = timestamp,
        heart_rate = heartRate,
        user_state = userState,
        drink_amount = drinkAmount.toString(),
        alcohol_percentage = alcoholPercentage
    )

    RetrofitClient.instance.sendSensorData(sensorData)
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(
            { response ->
                if (response.isSuccessful) {
                    Log.i("API", "✅ Data sent successfully")
                } else {
                    Log.e("API", "❌ API request failed with code: ${response.code()}")
                }
            },
            { error -> Log.e("API", "❌ API request failed", error) }  // ✅ 에러 처리 추가
        )
}
