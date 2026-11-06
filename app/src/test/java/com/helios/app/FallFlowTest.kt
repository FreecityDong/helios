package com.helios.app
import org.junit.Assert.*
import org.junit.Test

class FallFlowTest {
    @Test fun okAndCancelReleaseOnlyTheirActiveEpisode() {
        for(reply in listOf(FallReply.OK,FallReply.CANCEL)) {
            val f=FallFlow();val first=f.trigger(1000,"injected")!!
            assertNull(f.trigger(2000,"repeat"))
            assertEquals(FallEffectKind.RELEASE,f.reply(2200,reply)?.kind)
            assertEquals(FallState.RECOVERING,f.state)
            assertNull(f.reply(2300,FallReply.HELP))
            f.tick(40_000,false,false);assertEquals(FallState.RECOVERING,f.state)
            f.tick(40_100,false,true);assertEquals(FallState.IDLE,f.state)
            assertNotEquals(first.episode,f.trigger(41_000,"new")!!.episode)
        }
    }
    @Test fun explicitHelpDoesNotWaitForTimeoutAndCanBeCancelled() {
        val f=FallFlow();f.trigger(1000,"test")
        assertEquals(FallEffectKind.HELP,f.reply(1200,FallReply.HELP)?.kind)
        assertEquals(FallState.HELPING,f.state)
        assertEquals(FallEffectKind.RELEASE,f.reply(1400,FallReply.CANCEL)?.kind)
        assertNull(f.tick(60_000,true,true))
    }
    @Test fun unknownWordsDoNotDismissAnEvent() {
        val f=FallFlow();f.trigger(1000,"test")
        assertNull(f.reply(1200,FallFlow.parse("前方可以通行")))
        assertEquals(FallState.CONFIRMING,f.state)
        assertEquals(FallReply.OK,FallFlow.parse("我 没事。"))
        assertEquals(FallReply.HELP,FallFlow.parse("需要 帮助"))
        assertEquals(FallReply.CANCEL,FallFlow.parse("取消 求助"))
    }
    @Test fun noResponseCountsOnlyReadyInputAndAnnouncesHelpBeforeStartingIt() {
        val f=FallFlow();f.trigger(1000,"test")
        val actions=mutableListOf<FallEffectKind>()
        for(now in 1100L..31_200L step 100) f.tick(now,now<11_100 || now>=21_100,true)?.let {actions.add(it.kind)}
        assertEquals(listOf(FallEffectKind.WARN,FallEffectKind.HELP),actions)
        assertEquals(20_000,f.listenedMs)
        assertEquals(FallState.HELPING,f.state)
    }
    @Test fun audioFailureDoesNotCountAsListeningButWallClockStillEndsConfirmation() {
        val f=FallFlow();f.trigger(1000,"test")
        val actions=(1100L..41_100L step 100).mapNotNull {f.tick(it,false,true)?.kind}
        assertEquals(listOf(FallEffectKind.WARN,FallEffectKind.HELP),actions)
        assertEquals(0,f.listenedMs)
    }
    @Test fun helperVoiceLeavesInputGapsAndContinuousSpeechCannotRepeat() {
        val f=FallFlow();f.trigger(1000,"test");f.reply(1200,FallReply.HELP)
        assertNull(f.tick(12_000,false,true))
        for(now in 12_100L..18_000L step 100) assertNull(f.tick(now,true,true))
        assertEquals(FallEffectKind.HELP,f.tick(18_100,true,true)?.kind)
        assertNull(f.tick(18_200,true,true))
    }
    @Test fun pauseAndRepeatedSignalsCannotRestartTheSameEvent() {
        val f=FallFlow();f.trigger(1000,"test");f.cancel(1500)
        assertNull(f.trigger(2000,"repeat"))
        assertNull(f.tick(20_000,true,true))
        assertEquals(FallState.RECOVERING,f.state)
    }
}
