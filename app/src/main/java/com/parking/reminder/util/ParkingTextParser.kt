package com.parking.reminder.util

/**
 * 음성 인식된 텍스트를 분석하여 층수와 기둥 번호를 추출하는 자연어 파서
 */
object ParkingTextParser {

    data class ParsedResult(
        val matchedFloor: String?, // Spinner 항목과 매칭되는 전체 문자열 (예: "지하 2층 (B2)")
        val floorIndex: Int,       // Spinner 인덱스 (0~15)
        val pillar: String         // 기둥 번호 (예: "A", "A12", "05")
    )

    // B6층부터 10층까지의 기준 목록
    private val FLOOR_LIST = listOf(
        "지하 6층 (B6)", "지하 5층 (B5)", "지하 4층 (B4)", "지하 3층 (B3)",
        "지하 2층 (B2)", "지하 1층 (B1)", "지상 1층 (1F)", "지상 2층 (2F)",
        "지상 3층 (3F)", "지상 4층 (4F)", "지상 5층 (5F)", "지상 6층 (6F)",
        "지상 7층 (7F)", "지상 8층 (8F)", "지상 9층 (9F)", "지상 10층 (10F)"
    )

    /**
     * 발화된 음성 문장을 파싱
     * 예: "지하 2층 A기둥", "지하 1층 에이 12번", "지상 3층 15기둥", "비2 A구역"
     */
    fun parse(spokenText: String): ParsedResult? {
        val normalized = spokenText
            .trim()
            .replace("\\s+".toRegex(), " ") // 다중 공백 단일화

        // 1. 층수 추출
        val floorMatch = extractFloor(normalized) ?: return null

        // 2. 발화문에서 층수 표현 부분 제거 후 기둥 번호 추출
        var remaining = normalized.replace(floorMatch.rawPattern, "").trim()

        // 3. 기둥 번호 정제 (한글 발음 알파벳 치환 및 특수문자/조사 정리)
        val pillar = extractPillar(remaining)

        return ParsedResult(
            matchedFloor = FLOOR_LIST.getOrNull(floorMatch.index),
            floorIndex = floorMatch.index,
            pillar = if (pillar.isNotEmpty()) pillar else "미지정"
        )
    }

    private data class FloorMatch(val index: Int, val rawPattern: String)

    private fun extractFloor(text: String): FloorMatch? {
        // 지하 N층 패턴 (예: "지하 2층", "지하2층", "지하 이층", "지하 2", "B2", "b2", "비투", "비이")
        val basementRegex = "(?:지하\\s*([1-6]|일|이|삼|사|오|육|원|투|쓰리|포|파이브|식스)(?:층)?|[bB비](?:하)?\\s*([1-6]|일|이|삼|사|오|육|원|투|쓰리|포|파이브|식스)(?:층)?)".toRegex()
        val baseMatch = basementRegex.find(text)
        if (baseMatch != null) {
            val numStr = baseMatch.groupValues[1].ifEmpty { baseMatch.groupValues[2] }
            val num = convertNumber(numStr)
            if (num in 1..6) {
                // B6: 0, B5: 1, B4: 2, B3: 3, B2: 4, B1: 5
                val index = 6 - num
                return FloorMatch(index, baseMatch.value)
            }
        }

        // 지상 N층 패턴 (예: "지상 3층", "지상 삼층", "3층", "삼층", "지상10층", "10층", "2F", "3F")
        val groundRegex = "(?:지상\\s*)?([1-9]|10|일|이|삼|사|오|육|칠|팔|구|십)\\s*(?:층|[fF])".toRegex()
        val groundMatch = groundRegex.find(text)
        if (groundMatch != null) {
            val numStr = groundMatch.groupValues[1]
            val num = convertNumber(numStr)
            if (num in 1..10) {
                // 1F: 6, 2F: 7, ..., 10F: 15
                val index = 5 + num
                return FloorMatch(index, groundMatch.value)
            }
        }

        return null
    }

    private fun convertNumber(str: String): Int {
        return when (str) {
            "1", "일", "원" -> 1
            "2", "이", "투" -> 2
            "3", "삼", "쓰리" -> 3
            "4", "사", "포" -> 4
            "5", "오", "파이브" -> 5
            "6", "육", "식스" -> 6
            "7", "칠", "세븐" -> 7
            "8", "팔", "에이트" -> 8
            "9", "구", "나인" -> 9
            "10", "십", "텐" -> 10
            else -> str.toIntOrNull() ?: 0
        }
    }

    private fun extractPillar(text: String): String {
        var clean = text
            .replace("[-:=,#/\n\r]+".toRegex(), " ") // 특수문자 및 줄바꿈 공백화
            // "기둥", "번", "구역", "라인", "에", "의", "주차" 등 불필요한 단어/조사 제거
            .replace("(기둥|번|구역|라인|자리|에|의|주차)".toRegex(), " ")
            .trim()

        // 한국어 음성 인식 시 알파벳 한글 표기를 영문 대문자로 변환
        clean = clean
            .replace("에이", "A", ignoreCase = true)
            .replace("비", "B", ignoreCase = true)
            .replace("씨|시", "C", ignoreCase = true)
            .replace("디", "D", ignoreCase = true)
            .replace("이", "E", ignoreCase = true)
            .replace("에프", "F", ignoreCase = true)
            .replace("지", "G", ignoreCase = true)
            .replace("에이치", "H", ignoreCase = true)
            .replace("아이", "I", ignoreCase = true)
            .replace("제이", "J", ignoreCase = true)
            .replace("케이", "K", ignoreCase = true)
            .replace("엘", "L", ignoreCase = true)
            .replace("엠", "M", ignoreCase = true)
            .replace("엔", "N", ignoreCase = true)
            .replace("오", "O", ignoreCase = true)
            .replace("피", "P", ignoreCase = true)
            .replace("큐", "Q", ignoreCase = true)
            .replace("알", "R", ignoreCase = true)
            .replace("에스", "S", ignoreCase = true)
            .replace("티", "T", ignoreCase = true)
            .replace("유", "U", ignoreCase = true)
            .replace("브이", "V", ignoreCase = true)
            .replace("더블유", "W", ignoreCase = true)
            .replace("엑스", "X", ignoreCase = true)
            .replace("와이", "Y", ignoreCase = true)
            .replace("제트", "Z", ignoreCase = true)
            .replace("\\s+".toRegex(), "")
            .uppercase()

        return clean
    }
}
