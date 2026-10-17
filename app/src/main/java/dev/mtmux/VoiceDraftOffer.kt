package dev.mtmux

enum class VoiceDraftOffer { TARGET_CHANGED, NO_SPEECH, INSERTED, NEEDS_CHOICE }

fun decideVoiceDraftOffer(capturedToken:String,capturedTarget:String,currentToken:String,currentTarget:String,
                          currentDraft:String,transcript:String):VoiceDraftOffer = when {
    capturedToken!=currentToken || capturedTarget!=currentTarget -> VoiceDraftOffer.TARGET_CHANGED
    transcript.isBlank() -> VoiceDraftOffer.NO_SPEECH
    currentDraft.isBlank() -> VoiceDraftOffer.INSERTED
    else -> VoiceDraftOffer.NEEDS_CHOICE
}

data class VoiceDraftCommit(val accepted:Boolean,val draft:String)
fun commitVoiceDraft(capturedToken:String,capturedTarget:String,currentToken:String,currentTarget:String,
                     currentDraft:String,transcript:String,append:Boolean):VoiceDraftCommit {
    if(capturedToken!=currentToken || capturedTarget!=currentTarget) return VoiceDraftCommit(false,currentDraft)
    return VoiceDraftCommit(true,if(append && currentDraft.isNotBlank()) currentDraft+"\n"+transcript else transcript)
}
