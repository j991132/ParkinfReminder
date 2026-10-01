package com.parking.reminder.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.parking.reminder.data.ParkingPreferences
import java.util.Locale

/**
 * 안드로이드 내장 STT(SpeechRecognizer) 및 TTS(TextToSpeech) 통합 관리자
 */
class SpeechManager(private val context: Context) {

    private val preferences = ParkingPreferences(context)
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

    init {
        initTts()
    }

    /**
     * TTS 엔진 초기화
     */
    private fun initTts() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.KOREAN)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("SpeechManager", "TTS: 한국어 언어 팩이 지원되지 않습니다.")
                    isTtsReady = false
                } else {
                    isTtsReady = true
                    textToSpeech?.setSpeechRate(1.0f) // 일반 말하기 속도
                    textToSpeech?.setPitch(1.0f)
                }
            } else {
                Log.e("SpeechManager", "TTS 초기화 실패")
                isTtsReady = false
            }
        }
    }

    /**
     * TTS 음성 안내 출력
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!preferences.isTtsEnabled()) {
            Log.i("SpeechManager", "TTS가 사용자에 의해 꺼져 있습니다.")
            if (onDone != null) {
                Handler(Looper.getMainLooper()).post { onDone() }
            }
            return
        }

        if (!isTtsReady || textToSpeech == null) {
            Log.w("SpeechManager", "TTS가 준비되지 않았습니다.")
            if (onDone != null) {
                Handler(Looper.getMainLooper()).post { onDone() }
            }
            return
        }

        val utteranceId = System.currentTimeMillis().toString()

        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId && onDone != null) {
                    Handler(Looper.getMainLooper()).post {
                        onDone()
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (onDone != null) {
                    Handler(Looper.getMainLooper()).post {
                        onDone()
                    }
                }
            }
        })

        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * STT 음성 인식 시작
     */
    fun startListening(
        onReady: () -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("기기에서 음성 인식을 지원하지 않습니다.")
            return
        }

        // 기존 인스턴스 정리
        stopListening()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    onReady()
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    val message = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "오디오 녹음 중 오류가 발생했습니다."
                        SpeechRecognizer.ERROR_CLIENT -> "클라이언트 오류가 발생했습니다."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요합니다."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 연결을 확인해 주세요."
                        SpeechRecognizer.ERROR_NO_MATCH -> "음성을 인식하지 못했습니다. 다시 말씀해 주세요."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식기가 사용 중입니다."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말씀이 없어 음성 인식을 종료합니다."
                        else -> "음성 인식 중 오류가 발생했습니다. ($error)"
                    }
                    onError(message)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        // 한국어 음성 인식 결과 중 주차 층수/기둥 번호 매칭이 가능한 최선의 결과 검색
                        val bestMatch = matches.firstOrNull { candidate ->
                            ParkingTextParser.parse(candidate)?.matchedFloor != null
                        } ?: matches[0]
                        onResult(bestMatch)
                    } else {
                        onError("음성 결과가 없습니다.")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("ko-KR"))
            putExtra(RecognizerIntent.EXTRA_PROMPT, "주차 위치를 말씀해 주세요 (예: 지하 2층 A기둥)")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        speechRecognizer?.startListening(intent)
    }

    /**
     * STT 음성 인식 중지
     */
    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            speechRecognizer = null
        }
    }

    /**
     * 전체 리소스 해제 (Activity onDestroy에서 호출)
     */
    fun destroy() {
        stopListening()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isTtsReady = false
    }
}
