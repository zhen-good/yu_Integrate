package com.example.thelastone.data.mapper

import com.example.thelastone.data.model.QuestionV2Dto
import com.example.thelastone.data.model.*



object QuestionMapper {
    fun fromV2(dto: QuestionV2Dto): SingleChoiceQuestion {
        val id = dto.questionId ?: "q_${dto.text.hashCode()}"
        val opts = dto.options
            .sortedBy { it.choice }
            .map { ChoiceOption(it.choice, it.label ?: it.value ?: it.choice, it.value, it.key) }
        return SingleChoiceQuestion(id = id, text = dto.text, options = opts)
    }

    fun fromLegacy(dto: LegacyQuestionDto): SingleChoiceQuestion? {
        val qText = dto.question ?: return null
        val qId = dto.questionId ?: "legacy_${qText.hashCode()}"
        val opts = dto.choices.orEmpty()
            .sortedBy { it.id }
            .mapNotNull {
                val c = it.id ?: return@mapNotNull null
                ChoiceOption(c, it.text ?: it.value ?: c, it.value)
            }
        if (opts.isEmpty()) return null
        return SingleChoiceQuestion(id = qId, text = qText, options = opts)
    }
}