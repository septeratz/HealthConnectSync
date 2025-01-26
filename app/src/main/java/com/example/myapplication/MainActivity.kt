package com.example.myapplication

import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity(), DataClient.OnDataChangedListener, SensorEventListener {

    private var healthConnectClient: HealthConnectClient? = null
    private val permissions = setOf(
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class)
    )


    private val csvHeader = "Timestamp,Data Type,Value\n"
    private val csvFileName = "health_data.csv"

    private lateinit var dataClient: DataClient
    private lateinit var sensorManager: SensorManager
    private lateinit var scrollView: ScrollView
    private lateinit var textView: TextView

    private var heartRateSensor: Sensor? = null
    private var stepCounterSensor: Sensor? = null

    private val handler = Handler(Looper.getMainLooper()) // 반복 실행을 위한 핸들러
    private val updateInterval = 1000L // 5분 간격 (밀리초)

    private var currentHeartRate: Int = 0 // 실시간 심박수 저장
    private var currentStepCount: Long = 0 // 실시간 걸음 수 저장

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // UI 구성
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scrollView = ScrollView(this)
        textView = TextView(this).apply {
            text = "Initializing...\n"
        }
        scrollView.addView(textView)

        val requestPermissionButton = Button(this).apply {
            text = "권한 요청"
        }
        val startRecordingButton = Button(this).apply {
            text = "데이터 기록 시작"
        }
        val stopRecordingButton = Button(this).apply {
            text = "데이터 기록 중지"
        }

        // DataClient 초기화
        dataClient = Wearable.getDataClient(this)

        // 데이터 리스너 등록
        dataClient.addListener(this)

        layout.addView(requestPermissionButton)
        layout.addView(startRecordingButton)
        layout.addView(stopRecordingButton)
        layout.addView(scrollView)
        setContentView(layout)

        // HealthConnectClient 초기화
        CoroutineScope(Dispatchers.Main).launch {
            try {
                healthConnectClient = HealthConnectClient.getOrCreate(this@MainActivity)
                textView.append("Health Connect Client 생성 완료.\n")
            } catch (e: Exception) {
                e.printStackTrace()
                textView.append("Health Connect 생성 실패: ${e.message}\n")
            }
        }

        // 센서 초기화
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        // 권한 요청
        requestPermissionButton.setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                if (healthConnectClient == null) {
                    textView.append("Health Connect 클라이언트가 없습니다.\n")
                    return@launch
                }
                val granted = healthConnectClient!!.permissionController.getGrantedPermissions()
                if (!granted.containsAll(permissions)) {
                    registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { grantedPermissions ->
                        if (grantedPermissions.containsAll(permissions)) {
                            textView.append("모든 권한이 허용되었습니다.\n")
                        } else {
                            textView.append("권한 일부가 거부되었습니다.\n")
                        }
                    }.launch(permissions)
                } else {
                    textView.append("이미 모든 권한이 허용되었습니다.\n")
                }
            }
        }

        // 데이터 기록 시작
        startRecordingButton.setOnClickListener {
            textView.append("데이터 기록을 시작합니다...\n")
            sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL)
            sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_NORMAL)
            handler.post(dataRecordingRunnable(textView, scrollView))
        }

        // 데이터 기록 중지
        stopRecordingButton.setOnClickListener {
            textView.append("데이터 기록을 중지합니다.\n")
            sensorManager.unregisterListener(this)
            handler.removeCallbacksAndMessages(null)
        }


    }
    private fun saveToCsv(dataType: String, value: String, timestamp: String? = null) {
        try {
            val directory = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            if (!directory?.exists()!!) {
                directory.mkdir()
            }

            val csvFile = File(directory, csvFileName)

            // 파일이 없으면 헤더 추가
            if (!csvFile.exists()) {
                csvFile.createNewFile()
                csvFile.appendText(csvHeader)
            }

            // 데이터 추가
            val row = "${timestamp ?: getCurrentTimestamp()},$dataType,$value\n"
            csvFile.appendText(row)

            runOnUiThread {
                textView.append("CSV에 저장: $row")
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            runOnUiThread {
                textView.append("CSV 저장 실패: ${e.message}\n")
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    private fun getCurrentTimestamp(): String {
        return Instant.now()
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }

    private fun dataRecordingRunnable(textView: TextView, scrollView: ScrollView): Runnable {
        return object : Runnable {
            override fun run() {
                recordHeartRateData(textView, scrollView)
                recordStepsData(textView, scrollView)

                // 5분 후 다시 실행
                handler.postDelayed(this, updateInterval)
            }
        }
    }

    private fun recordHeartRateData(textView: TextView, scrollView: ScrollView) {
        CoroutineScope(Dispatchers.IO).launch {
            if (healthConnectClient == null) return@launch
            try {
                val now = Instant.now()
                val heartRateRecord = HeartRateRecord(
                    startTime = now.minusSeconds(5),
                    endTime = now,
                    samples = listOf(
                        HeartRateRecord.Sample(
                            time = now,
                            beatsPerMinute = currentHeartRate.toLong() // 센서에서 읽은 심박수 값
                        )
                    ),
                    startZoneOffset = ZoneOffset.UTC,
                    endZoneOffset = ZoneOffset.UTC
                )

                healthConnectClient!!.insertRecords(listOf(heartRateRecord))

                CoroutineScope(Dispatchers.Main).launch {
                    textView.append("심박수 데이터 기록 완료: $currentHeartRate bpm\n")
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                CoroutineScope(Dispatchers.Main).launch {
                    textView.append("심박수 데이터 기록 오류: ${e.message}\n")
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun recordStepsData(textView: TextView, scrollView: ScrollView) {
        CoroutineScope(Dispatchers.IO).launch {
            if (healthConnectClient == null) return@launch
            try {
                val now = Instant.now()
                val stepsRecord = StepsRecord(
                    startTime = now.minusSeconds(300), // 5분 전
                    endTime = now,
                    count = currentStepCount, // 센서에서 읽은 걸음 수
                    startZoneOffset = ZoneOffset.UTC,
                    endZoneOffset = ZoneOffset.UTC
                )

                healthConnectClient!!.insertRecords(listOf(stepsRecord))

                CoroutineScope(Dispatchers.Main).launch {
                    textView.append("걸음 수 데이터 기록 완료: $currentStepCount 걸음\n")
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                CoroutineScope(Dispatchers.Main).launch {
                    textView.append("걸음 수 데이터 기록 오류: ${e.message}\n")
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                currentHeartRate = event.values[0].toInt()
            }
            Sensor.TYPE_STEP_COUNTER -> {
                currentStepCount = event.values[0].toLong()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 정확도 변경 이벤트 처리 (필요시 구현)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val key = dataMap.getString("key") ?: continue
                val timestamp = Instant.ofEpochMilli(dataMap.getLong("timestamp"))
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

                when (key) {
                    "heart_rate" -> {
                        val heartRate = dataMap.getInt("value")
                        appendToTextView("심박수: $heartRate bpm")
                        saveToCsv("Heart Rate", heartRate.toString(), timestamp)

                    }
                    "skin_temperature" -> {
                        val skinTemperature = dataMap.getFloat("value")
                        appendToTextView("피부 온도: $skinTemperature°C")
                        saveToCsv("Skin Temperature", skinTemperature.toString(), timestamp)
                    }
                }
            }
        }
    }
    private fun appendToTextView(text: String) {
        runOnUiThread {
            textView.append("$text\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
