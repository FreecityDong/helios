package com.helios.app

/** Only unknown background padding may surround the complete, high confidence wake phrase. */
internal class WakePhrasePolicy {
    data class Word(val text: String, val confidence: Double)
    data class Decision(val accepted: Boolean, val confidence: Double, val reason: String)
    private var candidateSince: Long? = null

    fun inspect(text: String, words: List<Word>, partial: Boolean, nowMs: Long): Decision {
        val phrase = text.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it != "[unk]" }
        val spoken = words.filter { it.text != "[unk]" }
        val confidence = spoken.minOfOrNull { it.confidence.takeIf { c -> c.isFinite() && c in 0.0..1.0 } ?: 0.0 } ?: 0.0
        val rejection = when {
            phrase != listOf("hi", "helios") -> "phrase_mismatch"
            spoken.map { it.text } != phrase -> "word_metadata"
            confidence < 0.80 -> "low_confidence"
            else -> null
        }
        if (rejection != null) {
            candidateSince = null
            return Decision(false, confidence, rejection)
        }
        if (!partial) { candidateSince = null; return Decision(true, confidence, "final_accepted") }
        val since = candidateSince
        if (since == null) candidateSince = nowMs
        val stable = since != null && nowMs - since >= 250L
        return Decision(stable, confidence, if (stable) "partial_accepted" else "partial_stabilizing")
    }
}
