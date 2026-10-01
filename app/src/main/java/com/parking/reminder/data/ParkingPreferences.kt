package com.parking.reminder.data

import android.content.Context
import android.content.SharedPreferences
import com.parking.reminder.model.ParkingLocation
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences 기반 로컬 데이터 저장 및 캐시 관리 클래스
 */
class ParkingPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "parking_reminder_prefs"
        private const val KEY_CURRENT_ID = "current_id"
        private const val KEY_CURRENT_FLOOR = "current_floor"
        private const val KEY_CURRENT_PILLAR = "current_pillar"
        private const val KEY_CURRENT_TIMESTAMP = "current_timestamp"
        private const val KEY_CURRENT_USER = "current_user"
        private const val KEY_HISTORY_JSON = "history_json"
        private const val KEY_FAMILY_GROUP_ID = "family_group_id"
        private const val KEY_USER_NICKNAME = "user_nickname"
        private const val KEY_TTS_ENABLED = "tts_enabled"
    }

    /**
     * 현재 주차 위치 저장
     */
    fun saveCurrentParking(location: ParkingLocation) {
        prefs.edit().apply {
            putString(KEY_CURRENT_ID, location.id)
            putString(KEY_CURRENT_FLOOR, location.floor)
            putString(KEY_CURRENT_PILLAR, location.pillar)
            putLong(KEY_CURRENT_TIMESTAMP, location.timestamp)
            putString(KEY_CURRENT_USER, location.registeredBy)
            apply()
        }
        // 히스토리에도 추가
        addToHistory(location)
    }

    /**
     * 현재 주차 위치 가져오기
     */
    fun getCurrentParking(): ParkingLocation? {
        val floor = prefs.getString(KEY_CURRENT_FLOOR, null) ?: return null
        val pillar = prefs.getString(KEY_CURRENT_PILLAR, "") ?: ""
        val id = prefs.getString(KEY_CURRENT_ID, "") ?: ""
        val timestamp = prefs.getLong(KEY_CURRENT_TIMESTAMP, 0L)
        val registeredBy = prefs.getString(KEY_CURRENT_USER, "내 폰") ?: "내 폰"

        return ParkingLocation(
            id = id,
            floor = floor,
            pillar = pillar,
            timestamp = timestamp,
            registeredBy = registeredBy
        )
    }

    /**
     * 주차 히스토리 목록 가져오기
     */
    fun getHistoryList(): List<ParkingLocation> {
        val jsonStr = prefs.getString(KEY_HISTORY_JSON, "[]") ?: "[]"
        val list = mutableListOf<ParkingLocation>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    ParkingLocation(
                        id = obj.optString("id"),
                        floor = obj.optString("floor"),
                        pillar = obj.optString("pillar"),
                        timestamp = obj.optLong("timestamp"),
                        registeredBy = obj.optString("registeredBy", "내 폰")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    /**
     * 히스토리에 새 기록 추가 (최신순, 최대 50개 유지)
     */
    private fun addToHistory(location: ParkingLocation) {
        val currentList = getHistoryList().toMutableList()
        // 중복 방지 (동일 ID인 경우 제거 후 맨 앞에 추가)
        currentList.removeAll { it.id == location.id }
        currentList.add(0, location)

        // 최대 50개 제한
        val trimmedList = if (currentList.size > 50) currentList.subList(0, 50) else currentList
        saveHistoryList(trimmedList)
    }

    /**
     * 히스토리 특정 항목 삭제
     */
    fun deleteHistoryItem(id: String) {
        val currentList = getHistoryList().toMutableList()
        currentList.removeAll { it.id == id }
        saveHistoryList(currentList)

        // 만약 현재 주차된 위치를 삭제했다면 현재 위치도 초기화
        val current = getCurrentParking()
        if (current?.id == id) {
            clearCurrentParking()
        }
    }

    private fun saveHistoryList(list: List<ParkingLocation>) {
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("floor", item.floor)
                put("pillar", item.pillar)
                put("timestamp", item.timestamp)
                put("registeredBy", item.registeredBy)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY_JSON, jsonArray.toString()).apply()
    }

    fun clearCurrentParking() {
        prefs.edit().apply {
            remove(KEY_CURRENT_ID)
            remove(KEY_CURRENT_FLOOR)
            remove(KEY_CURRENT_PILLAR)
            remove(KEY_CURRENT_TIMESTAMP)
            remove(KEY_CURRENT_USER)
            apply()
        }
    }

    // 가족 그룹 ID
    fun getFamilyGroupId(): String? = prefs.getString(KEY_FAMILY_GROUP_ID, null)
    fun setFamilyGroupId(groupId: String) = prefs.edit().putString(KEY_FAMILY_GROUP_ID, groupId).apply()

    // 사용자 닉네임
    fun getUserNickname(): String = prefs.getString(KEY_USER_NICKNAME, "나") ?: "나"
    fun setUserNickname(nickname: String) = prefs.edit().putString(KEY_USER_NICKNAME, nickname).apply()

    // TTS 음성 안내 ON/OFF 설정
    fun isTtsEnabled(): Boolean = prefs.getBoolean(KEY_TTS_ENABLED, true)
    fun setTtsEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_TTS_ENABLED, enabled).apply()
}
