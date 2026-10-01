package com.parking.reminder.data

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import com.parking.reminder.model.ParkingLocation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Firebase Firestore 기반 실시간 데이터 연동 Repository
 * 오프라인 지하 주차장 캐싱 및 실시간 동기화 지원
 */
class FirebaseRepository {

    private val firestore: FirebaseFirestore by lazy {
        val db = FirebaseFirestore.getInstance()
        // 지하 주차장 등 오프라인 환경을 위한 디스크 영속성 캐시 설정
        val settings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
            .build()
        db.firestoreSettings = settings
        db
    }

    companion object {
        private const val TAG = "FirebaseRepository"
        private const val COLLECTION_GROUPS = "groups"
        private const val DOC_CURRENT = "current"
        private const val COLLECTION_HISTORY = "history"
    }

    /**
     * 가족 그룹의 '현재 주차 위치'를 실시간으로 관찰하는 Flow
     */
    fun observeCurrentParking(groupId: String): Flow<ParkingLocation?> = callbackFlow {
        if (groupId.isBlank()) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val docRef = firestore.collection(COLLECTION_GROUPS)
            .document(groupId)
            .collection(DOC_CURRENT)
            .document("latest")

        val listener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "현재 위치 실시간 감지 오류: ${error.message}")
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                val location = snapshot.toObject(ParkingLocation::class.java)
                trySend(location)
            } else {
                trySend(null)
            }
        }

        awaitClose { listener.remove() }
    }

    /**
     * 가족 그룹의 '주차 히스토리'를 실시간으로 관찰하는 Flow
     */
    fun observeParkingHistory(groupId: String): Flow<List<ParkingLocation>> = callbackFlow {
        if (groupId.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val query = firestore.collection(COLLECTION_GROUPS)
            .document(groupId)
            .collection(COLLECTION_HISTORY)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(50)

        val listener = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "히스토리 실시간 감지 오류: ${error.message}")
                return@addSnapshotListener
            }

            if (snapshot != null) {
                val list = snapshot.documents.mapNotNull { it.toObject(ParkingLocation::class.java) }
                trySend(list)
            }
        }

        awaitClose { listener.remove() }
    }

    /**
     * 새 주차 위치 저장 (현재 위치 문서 + 히스토리 컬렉션에 동시 반영)
     */
    suspend fun saveParkingLocation(groupId: String, location: ParkingLocation): Boolean {
        return try {
            val groupDoc = firestore.collection(COLLECTION_GROUPS).document(groupId)

            // 1. 현재 위치 문서 갱신
            groupDoc.collection(DOC_CURRENT)
                .document("latest")
                .set(location)
                .await()

            // 2. 히스토리 컬렉션에 추가
            groupDoc.collection(COLLECTION_HISTORY)
                .document(location.id)
                .set(location)
                .await()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Firestore 저장 실패: ${e.message}", e)
            false
        }
    }

    /**
     * 주차 위치 수정 (현재 위치가 수정 대상인 경우 함께 갱신)
     */
    suspend fun updateParkingLocation(groupId: String, location: ParkingLocation): Boolean {
        return try {
            val groupDoc = firestore.collection(COLLECTION_GROUPS).document(groupId)

            // 히스토리 내 문서 업데이트
            groupDoc.collection(COLLECTION_HISTORY)
                .document(location.id)
                .set(location)
                .await()

            // 현재 위치인지 확인 후 갱신
            val currentDoc = groupDoc.collection(DOC_CURRENT).document("latest").get().await()
            val current = currentDoc.toObject(ParkingLocation::class.java)
            if (current?.id == location.id) {
                groupDoc.collection(DOC_CURRENT).document("latest").set(location).await()
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Firestore 수정 실패: ${e.message}", e)
            false
        }
    }

    /**
     * 주차 기록 삭제
     */
    suspend fun deleteParkingLocation(groupId: String, locationId: String): Boolean {
        return try {
            val groupDoc = firestore.collection(COLLECTION_GROUPS).document(groupId)

            // 히스토리에서 삭제
            groupDoc.collection(COLLECTION_HISTORY)
                .document(locationId)
                .delete()
                .await()

            // 현재 위치가 삭제 대상인지 확인 후 삭제
            val currentDoc = groupDoc.collection(DOC_CURRENT).document("latest").get().await()
            val current = currentDoc.toObject(ParkingLocation::class.java)
            if (current?.id == locationId) {
                groupDoc.collection(DOC_CURRENT).document("latest").delete().await()
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Firestore 삭제 실패: ${e.message}", e)
            false
        }
    }
}
