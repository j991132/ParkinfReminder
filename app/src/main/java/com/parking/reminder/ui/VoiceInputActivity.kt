package com.parking.reminder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.parking.reminder.R
import com.parking.reminder.data.FirebaseRepository
import com.parking.reminder.data.ParkingPreferences
import com.parking.reminder.databinding.ActivityVoiceInputBinding
import com.parking.reminder.model.ParkingLocation
import com.parking.reminder.util.ParkingTextParser
import com.parking.reminder.util.SpeechManager
import com.parking.reminder.widget.ParkingWidgetProvider
import kotlinx.coroutines.launch

/**
 * 홈 화면 위젯에서 '음성 입력' 클릭 시 메인 앱 화면을 열지 않고
 * 반투명 플로팅 다이얼로그로 즉시 음성 입력을 수행하는 Activity
 */
class VoiceInputActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoiceInputBinding
    private lateinit var speechManager: SpeechManager
    private lateinit var preferences: ParkingPreferences
    private lateinit var firebaseRepository: FirebaseRepository

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startListening()
        } else {
            Toast.makeText(this, "음성 인식을 위해 마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceInputBinding.inflate(layoutInflater)
        setContentView(binding.root)

        speechManager = SpeechManager(this)
        preferences = ParkingPreferences(this)
        firebaseRepository = FirebaseRepository()

        binding.btnCancelVoice.setOnClickListener {
            speechManager.stopListening()
            finish()
        }

        checkAndRequestAudioPermission()
    }

    private fun checkAndRequestAudioPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        speechManager.startListening(
            onReady = {
                binding.tvVoiceStatusTitle.text = "🔴 듣고 있습니다..."
                binding.tvVoiceStatusDesc.text = "주차 위치를 말씀해 주세요\n(예: 지하 2층 A기둥)"
            },
            onResult = { recognizedText ->
                binding.tvVoiceStatusDesc.text = "\"$recognizedText\""

                val parsed = ParkingTextParser.parse(recognizedText)
                if (parsed != null && parsed.matchedFloor != null) {
                    val location = ParkingLocation(
                        floor = parsed.matchedFloor,
                        pillar = parsed.pillar,
                        registeredBy = preferences.getUserNickname()
                    )

                    // 로컬 저장 (현재 위치 + 히스토리)
                    preferences.saveCurrentParking(location)

                    // 클라우드 공유 저장
                    val groupId = preferences.getFamilyGroupId()
                    if (!groupId.isNullOrBlank()) {
                        lifecycleScope.launch {
                            firebaseRepository.saveParkingLocation(groupId, location)
                        }
                    }

                    // 위젯 강제 갱신
                    ParkingWidgetProvider.sendUpdateBroadcast(this)

                    val ttsMsg = getString(R.string.tts_save_success, parsed.matchedFloor, "${parsed.pillar}기둥")
                    binding.tvVoiceStatusTitle.text = "✅ 저장 완료!"
                    binding.tvVoiceStatusDesc.text = ttsMsg

                    speechManager.speak(ttsMsg) {
                        Handler(Looper.getMainLooper()).post {
                            if (!isFinishing) finish()
                        }
                    }

                    // 음성 안내가 비활성화되어 있거나 TTS 실패 시 안전 종료 백업 (6초)
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isFinishing) finish()
                    }, 6000)
                } else {
                    binding.tvVoiceStatusTitle.text = "⚠️ 인식 실패"
                    binding.tvVoiceStatusDesc.text = "층수나 기둥 정보를 인식하지 못했습니다.\n다시 시도해 주세요."
                    Handler(Looper.getMainLooper()).postDelayed({
                        finish()
                    }, 1800)
                }
            },
            onError = { errorMsg ->
                binding.tvVoiceStatusTitle.text = "⚠️ 음성 인식 오류"
                binding.tvVoiceStatusDesc.text = errorMsg
                Handler(Looper.getMainLooper()).postDelayed({
                    finish()
                }, 1800)
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager.destroy()
    }
}
