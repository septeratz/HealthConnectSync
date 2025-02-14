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
import android.text.InputType
import android.util.Log
import android.widget.*
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
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import com.amplifyframework.api.aws.AWSApiPlugin
import com.amplifyframework.core.Amplify
import com.amplifyframework.core.configuration.AmplifyOutputs
import com.amplifyframework.AmplifyException
import com.amplifyframework.api.graphql.model.ModelMutation
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.amplifyframework.datastore.generated.model.Todo
import com.example.myapplication.R.raw.amplify_outputs
import com.amplifyframework.datastore.AWSDataStorePlugin
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import org.json.JSONObject
import org.w3c.dom.Text


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

    private val handler = Handler(Looper.getMainLooper())
    private val updateInterval = 1000L // 주기적으로 기록할 간격 (예: 1000ms = 1초)

    private var currentHeartRate: Int = 0
    private var currentStepCount: Long = 0

    // ▼ 추가 UI 컴포넌트를 멤버 변수로 선언 (동적 생성 시 저장할 참조)
    private lateinit var stateSpinner: Spinner
    private lateinit var drinkAmountEditText: EditText
    private lateinit var alcoholPercentageEditText: EditText
    // ▲

    // ▼ 음주 잔 수 표시를 위한 TextView 추가
    private lateinit var drinkCountTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 레이아웃(LinearLayout) 동적 생성
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }


// onCreate() 내 레이아웃 구성 부분에 추가
        drinkCountTextView = TextView(this).apply {
            text = "현재 음주 잔 수: 0잔"
            textSize = 18f
            setPadding(0, 16, 0, 16)
        }
        layout.addView(drinkCountTextView) // 기존 레이아웃에 추가
        // 스크롤뷰, 로그창(TextView)
        scrollView = ScrollView(this)
        textView = TextView(this).apply {
            text = "Initializing...\n"
        }
        scrollView.addView(textView)

        // 버튼 3개
        val requestPermissionButton = Button(this).apply {
            text = "권한 요청"
        }
        val startRecordingButton = Button(this).apply {
            text = "데이터 기록 시작"
        }
        val stopRecordingButton = Button(this).apply {
            text = "데이터 기록 중지"
        }

        // DataClient 초기화 & 리스너 등록
        dataClient = Wearable.getDataClient(this)
        dataClient.addListener(this)

        // 레이아웃에 버튼 추가
        layout.addView(requestPermissionButton)
        layout.addView(startRecordingButton)
        layout.addView(stopRecordingButton)

        // ▼ 추가로 "현재 상태(평상시/음주 중)" Spinner
        stateSpinner = Spinner(this).apply {
            val states = listOf("평상시", "음주 중")
            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, states)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            setAdapter(adapter)
        }
        layout.addView(stateSpinner)

        // ▼ "음주량" EditText
        drinkAmountEditText = EditText(this).apply {
            hint = "음주량 (예: 2잔)"
        }
        layout.addView(drinkAmountEditText)

        // ▼ "술 도수" EditText
        alcoholPercentageEditText = EditText(this).apply {
            hint = "술 도수 (예: 20)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(alcoholPercentageEditText)

        // 스크롤뷰 마지막에 추가
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

        // 권한 요청 버튼
        requestPermissionButton.setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                if (healthConnectClient == null) {
                    textView.append("Health Connect 클라이언트가 없습니다.\n")
                    return@launch
                }
                val granted = healthConnectClient!!.permissionController.getGrantedPermissions()
                if (!granted.containsAll(permissions)) {
                    registerForActivityResult(
                        PermissionController.createRequestPermissionResultContract()
                    ) { grantedPermissions ->
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

        // 기록 시작 버튼
        startRecordingButton.setOnClickListener {
            textView.append("데이터 기록을 시작합니다...\n")
            handler.post(dataRecordingRunnable())
            // sendDataToAWS("2025-02-12T12:34:56Z", 75,"음주 중", 2, 12.5f) -> 테스트용, 주석 풀면 이렇게 데이터 들어감
        }

        // 기록 중지 버튼
        stopRecordingButton.setOnClickListener {
            textView.append("데이터 기록을 중지합니다.\n")
            sensorManager.unregisterListener(this)
            handler.removeCallbacksAndMessages(null)
        }
    }

    // 주기적으로(예: 1초) 실행되는 Runnable
    private fun dataRecordingRunnable(): Runnable {
        return object : Runnable {
            override fun run() {
                recordHeartRateData()
                // 다음 주기에 다시 실행
                handler.postDelayed(this, updateInterval)
            }
        }
    }

    // Health Connect에 심박수 기록
    private fun recordHeartRateData() {
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
                            beatsPerMinute = currentHeartRate.toLong()
                        )
                    ),
                    startZoneOffset = ZoneOffset.UTC,
                    endZoneOffset = ZoneOffset.UTC
                )

                healthConnectClient!!.insertRecords(listOf(heartRateRecord))
                appendToTextView("심박수 데이터 기록 완료: $currentHeartRate bpm")
            } catch (e: Exception) {
                e.printStackTrace()
                appendToTextView("심박수 데이터 기록 오류: ${e.message}")
            }
        }
    }

    // 센서 수신 콜백
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
        // 필요시 구현
    }

    // 워치 측으로부터 DataEvent 수신
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val timestamp = Instant.ofEpochMilli(dataMap.getLong("timestamp"))
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

                var receivedHeartRate: Int? = null
                var receivedDrinkCount: Int? = null

                // 🔹 심박수 데이터 수신
                if (dataMap.containsKey("heart_rate")) {
                    receivedHeartRate = dataMap.getInt("heart_rate")
                    Log.d("PhoneApp", "심박수 수신: $receivedHeartRate bpm")
                    runOnUiThread {
                        appendToTextView("심박수: $receivedHeartRate bpm")
                        saveToCsv("Heart Rate", receivedHeartRate.toString(), timestamp)
                    }
                }

                // 🔹 음주 잔 수 데이터 수신
                if (dataMap.containsKey("drink_count")) {
                    receivedDrinkCount = dataMap.getInt("drink_count")
                    Log.d("PhoneApp", "음주 잔 수 수신: $receivedDrinkCount")
                    runOnUiThread {
                        drinkCountTextView.text = "현재 음주 잔 수: ${receivedDrinkCount}잔"
                    }
                    saveToCsv("Drink Count", receivedDrinkCount.toString(), timestamp)
                }

                // 🔹 한 번만 서버로 전송 (둘 중 하나라도 값이 있을 경우)
                if (receivedHeartRate != null || receivedDrinkCount != null) {
                    sendDataToServer(timestamp, receivedHeartRate, receivedDrinkCount)

                }
            }
        }
    }





    // CSV 파일 저장
    private fun saveToCsv(dataType: String, value: String, timestamp: String? = null) {
        try {
            val directory = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            if (directory?.exists() == false) {
                directory.mkdir()
            }
            val csvFile = File(directory, csvFileName)

            // 파일이 없으면 헤더 추가
            if (!csvFile.exists()) {
                csvFile.createNewFile()
                csvFile.appendText(csvHeader)
            }

            val row = "${timestamp ?: getCurrentTimestamp()},$dataType,$value\n"
            csvFile.appendText(row)

            appendToTextView("CSV에 저장: $row")
        } catch (e: IOException) {
            e.printStackTrace()
            appendToTextView("CSV 저장 실패: ${e.message}")
        }
    }

    private fun getCurrentTimestamp(): String {
        return Instant.now()
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }

    // 텍스트뷰 로그 출력
    private fun appendToTextView(msg: String) {
        runOnUiThread {
            textView.append("$msg\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    // js 연동용 함수, 쓸 거면 본인 node 열어서 쓰기. 사용하지 않음
    private fun sendDataToJS(
        timestamp: String,
        heartRate: Int?,
        drinkCount: Int?,
        userState: String,
        drinkAmount: Int?,
        alcoholPercentage: Float?
    ) {
        val jsonData = JSONObject().apply {
            put("timestamp", timestamp)
            put("heart_rate", heartRate)
            put("drink_count", drinkCount)
            put("user_state", userState)
            put("drink_amount", drinkAmount)
            put("alcohol_percentage", alcoholPercentage)
        }

        val url = "https://your-nextjs-backend.com/api/sensor"

        val request = JsonObjectRequest(
            Request.Method.POST, url, jsonData,
            { response -> Log.i("Next.js API", "Data sent: $response") },
            { error -> Log.e("Next.js API", "Failed to send data", error) }
        )

        val requestQueue = Volley.newRequestQueue(this)
        requestQueue.add(request)
    }

    // ▼ 서버에 데이터를 전송하는 함수 (Retrofit 활용)
    private fun sendDataToServer(
        timestamp: String,
        heartRate: Int?,
        drinkCount: Int? = null  // 새로 추가된 파라미터
    ) {
        // Spinner/EditText에서 입력값 읽어오기
        val userState = stateSpinner.selectedItem.toString() // "평상시" or "음주 중"
        val alcoholPercentage = alcoholPercentageEditText.text.toString().toFloatOrNull()

        // aws로 전송. 바로 전송됨
        sendDataToAWS(timestamp, heartRate, userState, if (userState == "음주 중") drinkCount else null, if (userState == "음주 중") alcoholPercentage else null)

    }

}
