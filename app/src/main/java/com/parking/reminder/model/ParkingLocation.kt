package com.parking.reminder.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 주차 위치 정보 데이터 모델
 *
 * @property id 고유 식별자 (UUID 또는 Firestore 문서 ID)
 * @property floor 주차 층수 (예: "지하 2층 (B2)")
 * @property pillar 기둥 번호 (예: "A12")
 * @property timestamp 저장 시각 (밀리초)
 * @property registeredBy 등록자 (가족 구성원 닉네임 또는 "내 폰")
 */
data class ParkingLocation(
    val id: String = UUID.randomUUID().toString(),
    val floor: String = "",
    val pillar: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val registeredBy: String = "내 폰"
) {
    /**
     * 포맷팅된 등록 시각 반환 (예: 2026.09.30 15:30)
     */
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA)
            return sdf.format(Date(timestamp))
        }

    /**
     * 간략한 층수 코드 추출 (예: "지하 2층 (B2)" -> "B2")
     */
    val shortFloor: String
        get() {
            val regex = "\\((.*?)\\)".toRegex()
            val match = regex.find(floor)
            return match?.groups?.get(1)?.value ?: floor
        }

    /**
     * 한 줄 요약 텍스트 (예: "지하 2층 (B2) • A12 기둥")
     */
    val displaySummary: String
        get() = "$floor • $pillar 기둥"
}
