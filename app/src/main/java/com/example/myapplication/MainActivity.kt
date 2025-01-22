package com.example.myapplication

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.BuildCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private var healthConnectClient: HealthConnectClient? = null

    // 요청할 권한들
    private val permissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class)
    )

    // 버튼 클릭 시 실제 권한 요청을 보내는 Launcher
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Set<String>>

    @OptIn(BuildCompat.PrereleaseSdkCheck::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // UI 구성
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val textView = TextView(this).apply {
            text = "Initializing..."
        }
        val requestPermissionButton = Button(this).apply {
            text = "권한 요청"
        }
        val readDataButton = Button(this).apply {
            text = "심박수 데이터 읽기"
        }
        val writeDataButton = Button(this).apply {
            text = "걸음 수 데이터 쓰기"
        }

        layout.addView(requestPermissionButton)
        layout.addView(readDataButton)
        layout.addView(writeDataButton)
        layout.addView(textView)
        setContentView(layout)

        // HealthConnectClient 생성
        CoroutineScope(Dispatchers.Main).launch {
            try {
                healthConnectClient = HealthConnectClient.getOrCreate(this@MainActivity)
                textView.append("\nHealth Connect Client created.")
            } catch (e: Exception) {
                e.printStackTrace()
                textView.append("\nHealth Connect 생성 실패: ${e.message}")
            }
        }

        // registerForActivityResult 초기화
        requestPermissionLauncher =
            registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { grantedPermissions ->
                if (grantedPermissions.containsAll(permissions)) {
                    textView.append("\n모든 권한이 허용되었습니다.")
                } else {
                    textView.append("\n권한 일부가 거부되었습니다.")
                }
            }

        // 권한 요청 버튼 동작
        requestPermissionButton.setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                if (healthConnectClient == null) {
                    textView.append("\nHealth Connect 클라이언트가 없습니다.")
                    return@launch
                }
                val granted = healthConnectClient!!.permissionController.getGrantedPermissions()
                if (!granted.containsAll(permissions)) {
                    requestPermissionLauncher.launch(permissions)
                } else {
                    textView.append("\n이미 모든 권한이 허용되었습니다.")
                }
            }
        }
        val readOxygenButton = Button(this).apply {
            text = "산소 포화도 데이터 읽기"
        }
        val readTemperatureButton = Button(this).apply {
            text = "체온 데이터 읽기"
        }

        layout.addView(readOxygenButton)
        layout.addView(readTemperatureButton)

// 버튼 클릭 시 데이터 읽기 호출
        readOxygenButton.setOnClickListener {
            readOxygenSaturationData(textView)
        }
        readTemperatureButton.setOnClickListener {
            readBodyTemperatureData(textView)
        }

        // 심박수 데이터 읽기 버튼 동작
        readDataButton.setOnClickListener {
            readHeartRateData(textView)
        }

        // 걸음 수 데이터 쓰기 버튼 동작
        writeDataButton.setOnClickListener {
            writeStepsData(textView)
        }
    }

    private fun readHeartRateData(textView: TextView) {
        CoroutineScope(Dispatchers.IO).launch {
            if (healthConnectClient == null) return@launch
            try {
                val now = Instant.now()
                val oneDayAgo = now.minusSeconds(24 * 60 * 60) // 24시간 전
                val request = ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(oneDayAgo, now)
                )

                // 심박수 데이터 읽기
                val response = healthConnectClient!!.readRecords(request)
                val heartRateRecords = response.records

                // 메인 스레드에서 UI 업데이트
                CoroutineScope(Dispatchers.Main).launch {
                    if (heartRateRecords.isEmpty()) {
                        textView.append("\n심박수 데이터가 없습니다.")
                    } else {
                        textView.append("\n심박수 데이터:\n")
                        for (record in heartRateRecords) {
                            for (sample in record.samples) {
                                val heartRate = sample.beatsPerMinute
                                val timestamp = sample.time
                                textView.append("시간: $timestamp, 심박수: $heartRate bpm\n")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                CoroutineScope(Dispatchers.Main).launch {
                    textView.append("\n심박수 데이터 읽기 오류: ${e.message}")
                }
            }
        }
    }

    private fun readOxygenSaturationData(textView: TextView) {
        CoroutineScope(Dispatchers.IO).launch {
            if (healthConnectClient == null) return@launch
            try {
                val now = Instant.now()
                val oneDayAgo = now.minusSeconds(24 * 60 * 60) // 24시간 전
                val request = ReadRecordsRequest(
                    recordType = OxygenSaturationRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(oneDayAgo, now)
                )

                // 산소 포화도 데이터 읽기
                val response = healthConnectClient!!.readRecords(request)
                val oxygenRecords = response.records

                // 메인 스레드에서 UI 업데이트
                CoroutineScope(Dispatchers.Main).launch {
                    if (oxygenRecords.isEmpty()) {
                        textView.append("\n산소 포화도 데이터가 없습니다.")
                    } else {
                        textView.append("\n산소 포화도 데이터:\n")
                        for (record in oxygenRecords) {
                            val saturation = record.percentage
                            val timestamp = record.time
                            textView.append("시간: $timestamp, 산소 포화도: $saturation%\n")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                CoroutineScope(Dispatchers.Main).launch {
                    textView.append("\n산소 포화도 데이터 읽기 오류: ${e.message}")
                }
            }
        }
    }

    private fun readBodyTemperatureData(textView: TextView) {
        CoroutineScope(Dispatchers.IO).launch {
            if (healthConnectClient == null) return@launch
            try {
                val now = Instant.now()
                val oneDayAgo = now.minusSeconds(24 * 60 * 60) // 24시간 전
                val request = ReadRecordsRequest(
                    recordType = BodyTemperatureRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(oneDayAgo, now)
                )

                // 체온 데이터 읽기
                val response = healthConnectClient!!.readRecords(request)
                val temperatureRecords = response.records

                // 메인 스레드에서 UI 업데이트
                CoroutineScope(Dispatchers.Main).launch {
                    if (temperatureRecords.isEmpty()) {
                        textView.append("\n체온 데이터가 없습니다.")
                    } else {
                        textView.append("\n체온 데이터:\n")
                        for (record in temperatureRecords) {
                            val temperature = record.temperature
                            val timestamp = record.time
                            val measurementLocation = record.measurementLocation?.toString() ?: "알 수 없음"
                            textView.append("시간: $timestamp, 체온: $temperature°C, 측정 위치: $measurementLocation\n")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                CoroutineScope(Dispatchers.Main).launch {
                    textView.append("\n체온 데이터 읽기 오류: ${e.message}")
                }
            }
        }
    }

    private fun writeStepsData(textView: TextView) {
        CoroutineScope(Dispatchers.IO).launch {
            if (healthConnectClient == null) return@launch
            try {
                val now = Instant.now()
                val oneHourAgo = now.minusSeconds(3600) // 1시간 전
                val stepsRecord = StepsRecord(
                    startTime = oneHourAgo,
                    endTime = now, // 현재
                    count = Random.nextInt(100, 1000).toLong(),
                    startZoneOffset = java.time.ZoneOffset.systemDefault().rules.getOffset(oneHourAgo),
                    endZoneOffset = java.time.ZoneOffset.systemDefault().rules.getOffset(now)
                )

                CoroutineScope(Dispatchers.Main).launch {
                    textView.append("\n걸음 수 데이터 기록 완료.")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                CoroutineScope(Dispatchers.Main).launch {
                    textView.append("\n걸음 수 데이터 쓰기 오류: ${e.message}")
                }
            }
        }
    }
}
