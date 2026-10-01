package com.parking.reminder

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.parking.reminder.data.ParkingPreferences
import com.parking.reminder.databinding.ActivityMainBinding
import com.parking.reminder.model.ParkingLocation
import com.parking.reminder.ui.MainViewModel
import com.parking.reminder.ui.ParkingHistoryAdapter
import com.parking.reminder.ui.SwipeToDeleteCallback
import com.parking.reminder.util.ParkingTextParser
import com.parking.reminder.util.SpeechManager
import com.parking.reminder.widget.ParkingWidgetProvider
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var historyAdapter: ParkingHistoryAdapter
    private lateinit var speechManager: SpeechManager
    private lateinit var preferences: ParkingPreferences

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startVoiceRecognition()
        } else {
            Toast.makeText(this, "음성 인식을 위해 마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        speechManager = SpeechManager(this)
        preferences = ParkingPreferences(this)

        initViews()
        initRecyclerView()
        observeViewModel()
        handleWidgetIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(ParkingWidgetProvider.EXTRA_OPEN_SAVE, false) == true) {
            binding.etPillar.requestFocus()
            showKeyboard()
        } else if (intent?.getBooleanExtra(ParkingWidgetProvider.EXTRA_START_VOICE, false) == true) {
            binding.root.post {
                checkAndRequestAudioPermission()
            }
        }
    }

    private fun initViews() {
        val floorAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.parking_floors,
            R.layout.spinner_item
        ).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        binding.spinnerFloor.adapter = floorAdapter
        binding.spinnerFloor.setSelection(4) // 기본 지하 2층

        // 수동 위치 저장 버튼
        binding.btnSaveLocation.setOnClickListener {
            val selectedFloor = binding.spinnerFloor.selectedItem.toString()
            val pillar = binding.etPillar.text?.toString().orEmpty()

            val success = viewModel.saveParkingLocation(selectedFloor, pillar)
            if (success) {
                hideKeyboard()
                binding.etPillar.text?.clear()
                
                val speechMsg = getString(R.string.tts_save_success, selectedFloor, "${pillar}기둥")
                speechManager.speak(speechMsg)
                Toast.makeText(this, speechMsg, Toast.LENGTH_SHORT).show()
            }
        }

        // '내 차 찾기' 버튼
        binding.btnFindMyCar.setOnClickListener {
            showFindCarDialog(viewModel.currentParking.value)
        }

        // 음성 마이크 버튼
        binding.btnVoiceInput.setOnClickListener {
            checkAndRequestAudioPermission()
        }

        // 음성 읽어주기 (TTS) 토글 버튼
        updateTtsToggleIcon()
        binding.btnTtsToggle.setOnClickListener {
            val newStatus = !preferences.isTtsEnabled()
            preferences.setTtsEnabled(newStatus)
            updateTtsToggleIcon()
            val msgRes = if (newStatus) R.string.toast_tts_on else R.string.toast_tts_off
            Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
        }

        // 가족 그룹 설정 버튼
        binding.btnFamilyGroup.setOnClickListener {
            showFamilyGroupDialog()
        }
    }

    private fun updateTtsToggleIcon() {
        val isEnabled = preferences.isTtsEnabled()
        val iconRes = if (isEnabled) R.drawable.ic_volume_up else R.drawable.ic_volume_off
        binding.btnTtsToggle.setImageResource(iconRes)
    }

    private fun checkAndRequestAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                startVoiceRecognition()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("마이크 권한 안내")
                    .setMessage("주차 위치를 음성으로 입력하려면 마이크 권한 허용이 필요합니다.")
                    .setPositiveButton("권한 허용") { _, _ ->
                        requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
            else -> {
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startVoiceRecognition() {
        setMicButtonActive(true)

        speechManager.startListening(
            onReady = {
                binding.tvVoiceHint.text = "🔴 듣고 있습니다... (예: 지하 2층 A기둥)"
            },
            onResult = { recognizedText ->
                setMicButtonActive(false)
                binding.tvVoiceHint.text = "인식된 음성: \"$recognizedText\""

                val parsed = ParkingTextParser.parse(recognizedText)
                if (parsed != null && parsed.matchedFloor != null) {
                    binding.spinnerFloor.setSelection(parsed.floorIndex)
                    binding.etPillar.setText(parsed.pillar)

                    viewModel.saveParkingLocation(parsed.matchedFloor, parsed.pillar)

                    val ttsMsg = getString(R.string.tts_save_success, parsed.matchedFloor, "${parsed.pillar}기둥")
                    speechManager.speak(ttsMsg)
                    Toast.makeText(this, ttsMsg, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        "층수나 기둥 정보를 인식하지 못했습니다.\n예: '지하 2층 A기둥'으로 다시 말씀해 주세요.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            onError = { errorMsg ->
                setMicButtonActive(false)
                binding.tvVoiceHint.text = "💡 마이크를 누르고 '지하 2층 A기둥'처럼 말해보세요"
                Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun setMicButtonActive(isActive: Boolean) {
        val color = if (isActive) R.color.mic_active else R.color.primary
        binding.btnVoiceInput.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, color)
        )
    }

    private fun initRecyclerView() {
        historyAdapter = ParkingHistoryAdapter(
            onItemClick = { location ->
                showHistoryActionDialog(location)
            },
            onDeleteClick = { location ->
                confirmDeleteDialog(location)
            }
        )

        binding.rvParkingHistory.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = historyAdapter
        }

        val swipeHandler = object : SwipeToDeleteCallback(this) {
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val item = historyAdapter.currentList[position]
                confirmDeleteDialog(item) {
                    historyAdapter.notifyItemChanged(position)
                }
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(binding.rvParkingHistory)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 현재 주차 위치
                launch {
                    viewModel.currentParking.collect { location ->
                        updateCurrentLocationUI(location)
                    }
                }

                // 히스토리 목록
                launch {
                    viewModel.parkingHistory.collect { historyList ->
                        historyAdapter.submitList(historyList)
                        binding.tvEmptyHistory.isVisible = historyList.isEmpty()
                    }
                }

                // 그룹 연동 상태 헤더 반영
                launch {
                    viewModel.currentGroupId.collect { groupId ->
                        if (!groupId.isNullOrBlank()) {
                            binding.toolbar.subtitle = "가족 그룹: $groupId"
                        } else {
                            binding.toolbar.subtitle = "개인 모드 (가족 공유 미연결)"
                        }
                    }
                }

                // 토스트 메시지
                launch {
                    viewModel.toastEvent.collect { message ->
                        message?.let {
                            Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                            viewModel.clearToastEvent()
                        }
                    }
                }
            }
        }
    }

    private fun updateCurrentLocationUI(location: ParkingLocation?) {
        if (location != null) {
            binding.tvCurrentParkingDisplay.text = location.displaySummary
            binding.tvCurrentUpdateTime.text = "${location.formattedTime} (${location.registeredBy} 저장)"
            binding.btnFindMyCar.isEnabled = true
        } else {
            binding.tvCurrentParkingDisplay.text = "저장된 주차 위치가 없습니다."
            binding.tvCurrentUpdateTime.text = ""
            binding.btnFindMyCar.isEnabled = false
        }
    }

    private fun showFindCarDialog(location: ParkingLocation?) {
        if (location == null) {
            val emptyMsg = getString(R.string.tts_no_location)
            speechManager.speak(emptyMsg)

            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_find_car_title)
                .setMessage(R.string.dialog_find_car_empty)
                .setPositiveButton(R.string.dialog_close, null)
                .show()
            return
        }

        val ttsMsg = getString(R.string.tts_find_car_info, location.floor, "${location.pillar}기둥")
        speechManager.speak(ttsMsg)

        val message = getString(
            R.string.dialog_find_car_format,
            location.floor,
            "${location.pillar} 기둥",
            "${location.formattedTime} (${location.registeredBy})"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_find_car_title)
            .setMessage(message)
            .setIcon(R.drawable.ic_car)
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }

    private fun showHistoryActionDialog(location: ParkingLocation) {
        val options = arrayOf("상세 위치 확인 (음성 안내)", "위치 정보 수정", "기록 삭제")
        MaterialAlertDialogBuilder(this)
            .setTitle("${location.floor} ${location.pillar} 기둥")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showFindCarDialog(location)
                    1 -> showEditParkingDialog(location)
                    2 -> confirmDeleteDialog(location)
                }
            }
            .show()
    }

    private fun showEditParkingDialog(location: ParkingLocation) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_parking, null)
        val spinnerEditFloor = dialogView.findViewById<Spinner>(R.id.spinnerEditFloor)
        val etEditPillar = dialogView.findViewById<TextInputEditText>(R.id.etEditPillar)

        val editFloorAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.parking_floors,
            R.layout.spinner_item
        ).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        spinnerEditFloor.adapter = editFloorAdapter

        val floorArray = resources.getStringArray(R.array.parking_floors)
        val floorIndex = floorArray.indexOf(location.floor)
        if (floorIndex >= 0) spinnerEditFloor.setSelection(floorIndex)
        etEditPillar.setText(location.pillar)

        MaterialAlertDialogBuilder(this)
            .setTitle("주차 위치 수정")
            .setView(dialogView)
            .setPositiveButton("수정 완료") { _, _ ->
                val newFloor = spinnerEditFloor.selectedItem.toString()
                val newPillar = etEditPillar.text?.toString().orEmpty()
                if (newPillar.isNotBlank()) {
                    viewModel.updateParkingLocation(location, newFloor, newPillar)
                } else {
                    Toast.makeText(this, "기둥 번호를 입력해 주세요.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmDeleteDialog(location: ParkingLocation, onCancel: (() -> Unit)? = null) {
        MaterialAlertDialogBuilder(this)
            .setTitle("기록 삭제")
            .setMessage("${location.floor} ${location.pillar} 기둥 주차 기록을 삭제하시겠습니까?")
            .setNegativeButton("취소") { _, _ ->
                onCancel?.invoke()
            }
            .setOnCancelListener {
                onCancel?.invoke()
            }
            .setPositiveButton("삭제") { _, _ ->
                viewModel.deleteParkingLocation(location)
            }
            .show()
    }

    /**
     * [요구사항 6] 가족 공유 설정 다이얼로그 (초대 코드 생성 / 참여 / 클립보드 복사 / 카톡 공유)
     */
    private fun showFamilyGroupDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_family_share, null)
        val etUserNickname = dialogView.findViewById<TextInputEditText>(R.id.etUserNickname)
        val tvCurrentGroupId = dialogView.findViewById<TextView>(R.id.tvCurrentGroupId)
        val btnCopyGroupId = dialogView.findViewById<MaterialButton>(R.id.btnCopyGroupId)
        val btnShareInvite = dialogView.findViewById<ImageButton>(R.id.btnShareInvite)
        val btnLeaveGroup = dialogView.findViewById<TextView>(R.id.btnLeaveGroup)
        val etInputGroupId = dialogView.findViewById<TextInputEditText>(R.id.etInputGroupId)
        val btnJoinGroup = dialogView.findViewById<MaterialButton>(R.id.btnJoinGroup)
        val btnCreateNewGroup = dialogView.findViewById<MaterialButton>(R.id.btnCreateNewGroup)

        // 닉네임 설정 바인딩
        etUserNickname.setText(viewModel.getUserNickname())

        var dialog: AlertDialog? = null

        fun refreshGroupUI() {
            val groupId = viewModel.getFamilyGroupId()
            if (!groupId.isNullOrBlank()) {
                tvCurrentGroupId.text = "$groupId (연결됨)"
                btnCopyGroupId.isEnabled = true
                btnShareInvite.isEnabled = true
                btnLeaveGroup.isVisible = true
            } else {
                tvCurrentGroupId.text = "연결된 그룹 없음"
                btnCopyGroupId.isEnabled = false
                btnShareInvite.isEnabled = false
                btnLeaveGroup.isVisible = false
            }
        }

        refreshGroupUI()

        // 1. 코드 복사
        btnCopyGroupId.setOnClickListener {
            val groupId = viewModel.getFamilyGroupId()
            if (!groupId.isNullOrBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("가족 그룹 코드", groupId)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "그룹 코드($groupId)가 복사되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        // 2. 초대장 메시지 공유 (카카오톡, 문자 등)
        btnShareInvite.setOnClickListener {
            val groupId = viewModel.getFamilyGroupId()
            if (!groupId.isNullOrBlank()) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "🚗 [주차 위치 알림이]\n우리 가족 주차 공유 그룹 초대 코드입니다.\n코드: [$groupId]\n\n앱에서 코드를 입력하면 실시간으로 주차 위치를 공유할 수 있습니다!"
                    )
                }
                startActivity(Intent.createChooser(shareIntent, "가족에게 초대장 보내기"))
            }
        }

        // 3. 그룹 나가기
        btnLeaveGroup.setOnClickListener {
            viewModel.leaveFamilyGroup()
            refreshGroupUI()
        }

        // 4. 기존 코드 입력하여 참여
        btnJoinGroup.setOnClickListener {
            val code = etInputGroupId.text?.toString().orEmpty()
            if (viewModel.joinFamilyGroup(code)) {
                etInputGroupId.text?.clear()
                refreshGroupUI()
            }
        }

        // 5. 새 그룹 생성
        btnCreateNewGroup.setOnClickListener {
            viewModel.createNewFamilyGroup()
            refreshGroupUI()
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.title_family_share)
            .setView(dialogView)
            .setPositiveButton("닫기") { _, _ ->
                // 닉네임 저장
                val nickname = etUserNickname.text?.toString().orEmpty()
                viewModel.saveUserNickname(nickname)
            }
            .setOnDismissListener {
                val nickname = etUserNickname.text?.toString().orEmpty()
                viewModel.saveUserNickname(nickname)
            }
            .show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etPillar, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager.destroy()
    }
}
