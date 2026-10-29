package dev.mtmux

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Standard flavor: no button, setting, permission, model, or native dependency. */
@Composable internal fun VoiceInputControl(
    modifier: Modifier, enabled: Boolean, connectionToken: String, targetKey: String,
    onHideKeyboard: () -> Unit, onOfferDraft: (String,String,String) -> VoiceDraftOffer,
    onCommitDraft: (String,String,String,Boolean) -> Boolean,
    onStatus: (TerminalStatus) -> Unit
) = Unit

@Composable internal fun VoiceSettings() = Unit

internal fun initializeVoiceFlavor(context: android.content.Context) {
    // Switching from voice to standard must also release the extracted private voice models.
    kotlin.concurrent.thread(name="voice-model-cleanup",isDaemon=true) {
        runCatching { java.io.File(context.noBackupFilesDir,"voice").deleteRecursively() }
    }
}
