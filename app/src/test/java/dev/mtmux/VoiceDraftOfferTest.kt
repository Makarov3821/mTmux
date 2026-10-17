package dev.mtmux

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceDraftOfferTest {
    @Test fun decisionsProtectTargetAndExistingDraft() {
        assertEquals(VoiceDraftOffer.INSERTED,decideVoiceDraftOffer("t","p","t","p","","hello"))
        assertEquals(VoiceDraftOffer.NEEDS_CHOICE,decideVoiceDraftOffer("t","p","t","p","old","hello"))
        assertEquals(VoiceDraftOffer.TARGET_CHANGED,decideVoiceDraftOffer("t","p","other","p","","hello"))
        assertEquals(VoiceDraftOffer.TARGET_CHANGED,decideVoiceDraftOffer("t","p","t","other","","hello"))
        assertEquals(VoiceDraftOffer.NO_SPEECH,decideVoiceDraftOffer("t","p","t","p","old"," "))
    }
    @Test fun finalCommitRechecksIdentityAndKeepsLatestDraft() {
        assertEquals(VoiceDraftCommit(false,"new edit"),commitVoiceDraft("t","p","other","p","new edit","voice",true))
        assertEquals(VoiceDraftCommit(false,"new edit"),commitVoiceDraft("t","p","t","other","new edit","voice",false))
        assertEquals(VoiceDraftCommit(true,"new edit\nvoice"),commitVoiceDraft("t","p","t","p","new edit","voice",true))
        assertEquals(VoiceDraftCommit(true,"voice"),commitVoiceDraft("t","p","t","p","new edit","voice",false))
    }
}
