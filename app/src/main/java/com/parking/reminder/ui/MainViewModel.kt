package com.parking.reminder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.parking.reminder.data.FirebaseRepository
import com.parking.reminder.data.ParkingPreferences
import com.parking.reminder.model.ParkingLocation
import com.parking.reminder.widget.ParkingWidgetProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 가족 공유 및 Firestore 실시간 동기화 기능을 완성한 MainViewModel
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = ParkingPreferences(application)
    private val firebaseRepository = FirebaseRepository()

    private val _currentParking = MutableStateFlow<ParkingLocation?>(null)
    val currentParking: StateFlow<ParkingLocation?> = _currentParking.asStateFlow()

    private val _parkingHistory = MutableStateFlow<List<ParkingLocation>>(emptyList())
    val parkingHistory: StateFlow<List<ParkingLocation>> = _parkingHistory.asStateFlow()

    private val _toastEvent = MutableStateFlow<String?>(null)
    val toastEvent: StateFlow<String?> = _toastEvent.asStateFlow()

    private val _currentGroupId = MutableStateFlow<String?>(preferences.getFamilyGroupId())
    val currentGroupId: StateFlow<String?> = _currentGroupId.asStateFlow()

    private var firestoreObserveJob: Job? = null

    init {
        loadLocalData()
        startSyncWithGroup(_currentGroupId.value)
    }

    private fun loadLocalData() {
        _currentParking.value = preferences.getCurrentParking()
        _parkingHistory.value = preferences.getHistoryList()
    }

    /**
     * 가족 그룹 ID에 따른 실시간 동기화 리스너 바인딩
     */
    fun startSyncWithGroup(groupId: String?) {
        firestoreObserveJob?.cancel()
        _currentGroupId.value = groupId

        if (groupId.isNullOrBlank()) {
            loadLocalData()
            return
        }

        firestoreObserveJob = viewModelScope.launch {
            // 1. 현재 위치 실시간 관찰 (가족이 업데이트하면 즉시 내 화면/위젯 갱신)
            launch {
                firebaseRepository.observeCurrentParking(groupId).collect { location ->
                    if (location != null) {
                        _currentParking.value = location
                        preferences.saveCurrentParking(location)
                    } else if (_currentGroupId.value.isNullOrBlank()) {
                        _currentParking.value = preferences.getCurrentParking()
                    }
                    ParkingWidgetProvider.sendUpdateBroadcast(getApplication())
                }
            }

            // 2. 히스토리 실시간 관찰
            launch {
                firebaseRepository.observeParkingHistory(groupId).collect { list ->
                    if (list.isNotEmpty()) {
                        _parkingHistory.value = list
                    }
                }
            }
        }
    }

    /**
     * [요구사항 6] 새 6자리 가족 공유 그룹 생성
     */
    fun createNewFamilyGroup(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 혼동하기 쉬운 I, O, 1, 0 제외
        val code = (1..6)
            .map { chars[Random.nextInt(chars.length)] }
            .joinToString("")

        preferences.setFamilyGroupId(code)
        startSyncWithGroup(code)

        // 현재 주차된 위치가 있다면 새로 만든 그룹의 초기 데이터로 업로드
        _currentParking.value?.let { currentLoc ->
            viewModelScope.launch {
                firebaseRepository.saveParkingLocation(code, currentLoc)
            }
        }

        _toastEvent.value = "새 가족 그룹($code)이 생성되었습니다!"
        return code
    }

    /**
     * [요구사항 6] 기존 가족 그룹 초대 코드로 참여
     */
    fun joinFamilyGroup(code: String): Boolean {
        val cleanCode = code.trim().uppercase()
        if (cleanCode.length < 4) {
            _toastEvent.value = "유효한 그룹 코드를 입력해 주세요."
            return false
        }

        preferences.setFamilyGroupId(cleanCode)
        startSyncWithGroup(cleanCode)
        _toastEvent.value = "가족 그룹($cleanCode)에 연결되었습니다."
        return true
    }

    /**
     * 가족 그룹 연결 해제 (단독 로컬 모드로 전환)
     */
    fun leaveFamilyGroup() {
        preferences.setFamilyGroupId("")
        startSyncWithGroup(null)
        _toastEvent.value = "가족 그룹 연결이 해제되었습니다."
    }

    fun getUserNickname(): String = preferences.getUserNickname()
    fun saveUserNickname(nickname: String) {
        val clean = nickname.trim()
        if (clean.isNotEmpty()) {
            preferences.setUserNickname(clean)
        }
    }

    /**
     * 주차 위치 저장
     */
    fun saveParkingLocation(floor: String, pillar: String): Boolean {
        val cleanPillar = pillar.trim()
        if (cleanPillar.isEmpty()) {
            _toastEvent.value = "기둥 번호를 입력해 주세요."
            return false
        }

        val location = ParkingLocation(
            floor = floor,
            pillar = cleanPillar,
            timestamp = System.currentTimeMillis(),
            registeredBy = preferences.getUserNickname()
        )

        preferences.saveCurrentParking(location)
        _currentParking.value = location
        _parkingHistory.value = preferences.getHistoryList()
        ParkingWidgetProvider.sendUpdateBroadcast(getApplication())

        val groupId = _currentGroupId.value
        if (!groupId.isNullOrBlank()) {
            viewModelScope.launch {
                firebaseRepository.saveParkingLocation(groupId, location)
            }
        }

        return true
    }

    /**
     * 주차 위치 수정
     */
    fun updateParkingLocation(location: ParkingLocation, newFloor: String, newPillar: String) {
        val updated = location.copy(
            floor = newFloor,
            pillar = newPillar.trim()
        )

        preferences.saveCurrentParking(updated)
        _currentParking.value = preferences.getCurrentParking()
        _parkingHistory.value = preferences.getHistoryList()
        ParkingWidgetProvider.sendUpdateBroadcast(getApplication())

        val groupId = _currentGroupId.value
        if (!groupId.isNullOrBlank()) {
            viewModelScope.launch {
                firebaseRepository.updateParkingLocation(groupId, updated)
            }
        }
        _toastEvent.value = "기록이 수정되었습니다."
    }

    /**
     * 주차 기록 삭제
     */
    fun deleteParkingLocation(location: ParkingLocation) {
        viewModelScope.launch {
            preferences.deleteHistoryItem(location.id)
            _currentParking.value = preferences.getCurrentParking()
            _parkingHistory.value = preferences.getHistoryList()
            ParkingWidgetProvider.sendUpdateBroadcast(getApplication())

            val groupId = _currentGroupId.value
            if (!groupId.isNullOrBlank()) {
                firebaseRepository.deleteParkingLocation(groupId, location.id)
            }
            _toastEvent.value = "기록이 삭제되었습니다."
        }
    }

    fun clearToastEvent() {
        _toastEvent.value = null
    }

    fun getFamilyGroupId(): String? = preferences.getFamilyGroupId()
}
