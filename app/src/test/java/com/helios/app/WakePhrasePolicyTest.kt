package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class WakePhrasePolicyTest {
    private val wake = listOf(WakePhrasePolicy.Word("hi", .95), WakePhrasePolicy.Word("helios", .91))
    @Test fun finalCompletePhraseTriggersWithoutWaitingForAnotherSample() {
        assertTrue(WakePhrasePolicy().inspect("hi helios", wake, false, 0).accepted)
    }
    @Test fun stablePartialTriggersWithoutWaitingForSpeechEndpoint() {
        val policy = WakePhrasePolicy()
        assertFalse(policy.inspect("hi helios", wake, true, 100).accepted)
        assertFalse(policy.inspect("hi helios", wake, true, 300).accepted)
        assertTrue(policy.inspect("hi helios", wake, true, 350).accepted)
    }
    @Test fun unknownBackgroundDoesNotLowerConfidenceOfWakeWords() {
        val words = listOf(WakePhrasePolicy.Word("[unk]", .2)) + wake + WakePhrasePolicy.Word("[unk]", .1)
        val result = WakePhrasePolicy().inspect("[unk] hi helios [unk]", words, false, 0)
        assertTrue(result.accepted); assertEquals(.91, result.confidence, 0.001)
    }
    @Test fun lowConfidenceWakeWordIsStillRejected() {
        assertFalse(WakePhrasePolicy().inspect("[unk] hi helios", listOf(WakePhrasePolicy.Word("[unk]", 1.0), wake[0], WakePhrasePolicy.Word("helios", .79)), false, 0).accepted)
    }
    @Test fun partialWithoutWordConfidenceCannotTrigger() {
        val policy = WakePhrasePolicy()
        assertFalse(policy.inspect("hi helios", emptyList(), true, 0).accepted)
        assertFalse(policy.inspect("hi helios", emptyList(), true, 1000).accepted)
    }
    @Test fun changedOrIncompletePartialResetsStability() {
        val policy = WakePhrasePolicy()
        policy.inspect("hi helios", wake, true, 0)
        policy.inspect("hi", wake.take(1), true, 200)
        assertFalse(policy.inspect("hi helios", wake, true, 500).accepted)
        assertTrue(policy.inspect("hi helios", wake, true, 750).accepted)
    }
    @Test fun unrelatedRecognizedWordsCannotBeStrippedAsNoise() {
        assertFalse(WakePhrasePolicy().inspect("play hi helios", listOf(WakePhrasePolicy.Word("play", 1.0)) + wake, false, 0).accepted)
        assertFalse(WakePhrasePolicy().inspect("helios hi", wake.reversed(), false, 0).accepted)
    }
    @Test fun malformedConfidenceAndDisagreeingMetadataAreRejected() {
        assertFalse(WakePhrasePolicy().inspect("hi helios", listOf(wake[0], WakePhrasePolicy.Word("helios", Double.NaN)), false, 0).accepted)
        assertFalse(WakePhrasePolicy().inspect("hi helios", wake.take(1), false, 0).accepted)
    }
}
