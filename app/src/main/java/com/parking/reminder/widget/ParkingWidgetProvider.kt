package com.parking.reminder.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.RemoteViews
import android.widget.Toast
import com.parking.reminder.MainActivity
import com.parking.reminder.R
import com.parking.reminder.data.ParkingPreferences
import com.parking.reminder.ui.VoiceInputActivity
import java.util.Locale

/**
 * 스마트폰 홈 화면 주차 위치 알림 위젯 Provider
 */
open class ParkingWidgetProvider : AppWidgetProvider() {

    open val layoutId: Int = R.layout.widget_parking

    companion object {
        const val ACTION_FIND_CAR = "com.parking.reminder.ACTION_FIND_CAR"
        const val ACTION_UPDATE_DATA = "com.parking.reminder.ACTION_UPDATE_DATA"
        const val ACTION_TOGGLE_TTS = "com.parking.reminder.ACTION_TOGGLE_TTS"
        const val EXTRA_OPEN_SAVE = "extra_open_save"
        const val EXTRA_START_VOICE = "extra_start_voice"
        const val EXTRA_OPEN_FIND = "extra_open_find"

        /**
         * 앱 내부에서 주차 정보가 변경되었을 때 위젯을 즉시 갱신하는 헬퍼 메서드
         */
        fun sendUpdateBroadcast(context: Context) {
            context.sendBroadcast(Intent(context, ParkingWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_DATA
            })
            context.sendBroadcast(Intent(context, ParkingWidgetProvider2x1::class.java).apply {
                action = ACTION_UPDATE_DATA
            })
            context.sendBroadcast(Intent(context, ParkingWidgetProvider1x2::class.java).apply {
                action = ACTION_UPDATE_DATA
            })
        }
    }

    private var tts: TextToSpeech? = null

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val views = buildRemoteViews(context)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_UPDATE_DATA, AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                // 위젯 화면 강제 갱신
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(
                    ComponentName(context, this::class.java)
                )
                for (id in ids) {
                    val views = buildRemoteViews(context)
                    appWidgetManager.updateAppWidget(id, views)
                }
            }

            ACTION_FIND_CAR -> {
                // [요구사항 5] 앱을 켜지 않고도 위젯에서 바로 '내 차 찾기' 결과 확인 및 음성 안내
                val preferences = ParkingPreferences(context)
                val location = preferences.getCurrentParking()

                if (location != null) {
                    val message = "🅿️ ${location.floor} ${location.pillar}기둥에 주차되어 있습니다."
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()

                    // 위젯 백그라운드 TTS 음성 안내
                    speakText(context, "현재 주차 위치는 ${location.floor} ${location.pillar}기둥입니다.")
                } else {
                    val message = "저장된 주차 위치가 없습니다."
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    speakText(context, message)
                }
            }

            ACTION_TOGGLE_TTS -> {
                val preferences = ParkingPreferences(context)
                val newStatus = !preferences.isTtsEnabled()
                preferences.setTtsEnabled(newStatus)

                val message = if (newStatus) "🔊 위젯: 음성 안내가 켜졌습니다." else "🔇 위젯: 음성 안내가 꺼졌습니다."
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

                // 모든 크기 위젯 화면 강제 갱신
                sendUpdateBroadcast(context)
            }
        }
    }

    protected open fun buildRemoteViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, layoutId)
        val preferences = ParkingPreferences(context)
        val location = preferences.getCurrentParking()

        // 1. 위젯 텍스트 데이터 바인딩
        if (location != null) {
            views.setTextViewText(R.id.tvWidgetLocation, location.displaySummary)
            views.setTextViewText(R.id.tvWidgetUpdateTime, "${location.formattedTime} 저장")
        } else {
            views.setTextViewText(R.id.tvWidgetLocation, "저장된 주차 위치 없음")
            views.setTextViewText(R.id.tvWidgetUpdateTime, "")
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        // 2. '위치 저장' 버튼: 누르면 즉시 앱의 위치 입력 화면으로 이동
        val saveIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_SAVE, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val savePendingIntent = PendingIntent.getActivity(context, 101, saveIntent, flags)
        views.setOnClickPendingIntent(R.id.btnWidgetSave, savePendingIntent)

        // 3. '음성 입력' 버튼: 누르면 메인 앱으로 이동하지 않고 플로팅 음성 인식 팝업 바로 실행
        val voiceIntent = Intent(context, VoiceInputActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val voicePendingIntent = PendingIntent.getActivity(context, 103, voiceIntent, flags)
        views.setOnClickPendingIntent(R.id.btnWidgetVoice, voicePendingIntent)

        // 4. '내 차 찾기' 버튼: 누르면 브로드캐스트를 받아 토스트 + TTS 음성 안내 즉각 실행
        val findIntent = Intent(context, ParkingWidgetProvider::class.java).apply {
            action = ACTION_FIND_CAR
        }
        val findPendingIntent = PendingIntent.getBroadcast(context, 102, findIntent, flags)
        views.setOnClickPendingIntent(R.id.btnWidgetFind, findPendingIntent)

        // 5. 음성 안내 ON/OFF 스피커 아이콘 바인딩
        val isTtsEnabled = preferences.isTtsEnabled()
        val ttsIconRes = if (isTtsEnabled) R.drawable.ic_volume_up else R.drawable.ic_volume_off
        views.setImageViewResource(R.id.btnWidgetTtsToggle, ttsIconRes)

        val toggleIntent = Intent(context, ParkingWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_TTS
        }
        val togglePendingIntent = PendingIntent.getBroadcast(context, 104, toggleIntent, flags)
        views.setOnClickPendingIntent(R.id.btnWidgetTtsToggle, togglePendingIntent)

        // 6. 위젯 몸통 탭 시 앱 메인 화면 열기
        val mainIntent = Intent(context, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(context, 100, mainIntent, flags)
        views.setOnClickPendingIntent(R.id.widgetContainer, mainPendingIntent)

        return views
    }

    private fun speakText(context: Context, text: String) {
        val preferences = ParkingPreferences(context)
        if (!preferences.isTtsEnabled()) {
            return
        }

        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                tts?.setSpeechRate(1.0f) // 최신 기기(Galaxy S25 등)에서 위젯 TTS 속도가 지나치게 빠른 현상 방지
                tts?.setPitch(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        Handler(Looper.getMainLooper()).post {
                            try {
                                tts?.stop()
                                tts?.shutdown()
                                tts = null
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Handler(Looper.getMainLooper()).post {
                            try {
                                tts?.stop()
                                tts?.shutdown()
                                tts = null
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                })

                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "widget_tts_id")
                }
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "widget_tts_id")
            }
        }
    }
}
