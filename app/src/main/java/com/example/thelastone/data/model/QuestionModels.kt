package com.example.thelastone.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 後端事件：
 * - ai_question（可能是舊或新）
 * - ai_question_v2（一定是新）
 * 我們統一在前端轉成 Domain 模型 Question。
 */

/* -------------------- V2 schema（後端輸出） -------------------- */
@Serializable
data class QuestionV2Dto(
    @SerialName("schema_version") val schemaVersion: Int = 2,
    val type: QuestionTypeDto = QuestionTypeDto.SINGLE_CHOICE,
    @SerialName("question_id") val questionId: String,
    val text: String,
    val options: List<OptionV2Dto>,
    val constraints: ConstraintsDto? = null,
    val meta: Map<String, String>? = null
)

@Serializable
enum class QuestionTypeDto {
    @SerialName("single_choice") SINGLE_CHOICE,
    @SerialName("text") TEXT
}

@Serializable
data class OptionV2Dto(
    val choice: String,                 // "A","B","C"…
    val label: String? = null,          // 顯示文字
    val value: String? = null,          // 後端用值
    val key: String? = null             // 若有
)

@Serializable
data class ConstraintsDto(
    @SerialName("min_select") val minSelect: Int? = null,
    @SerialName("max_select") val maxSelect: Int? = null
)

/* -------------------- Legacy schema（舊後端可能送） -------------------- */
@Serializable
data class LegacyQuestionDto(
    val question: String? = null,
    val choices: List<LegacyChoiceDto>? = null,
    @SerialName("question_id") val questionId: String? = null
)

@Serializable
data class LegacyChoiceDto(
    val id: String? = null,        // "A"
    val text: String? = null,      // 顯示文字
    val value: String? = null      // 後端值
)

/* -------------------- Domain（App 內部統一使用） -------------------- */
sealed interface Question {
    val id: String
    val text: String
}

data class SingleChoiceQuestion(
    override val id: String,
    override val text: String,
    val options: List<ChoiceOption>
) : Question

data class ChoiceOption(
    val choice: String,        // "A" / "B" ...
    val label: String,         // 顯示文字
    val value: String? = null, // 可忽略
    val key: String? = null
)

data class AiQuestionEnvelope(
    @kotlinx.serialization.SerialName("user_id") val userId: String? = null,
    val message: QuestionV2Dto? = null
)

