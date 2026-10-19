package dev.mtmux

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mtmux.core.SshRetention
import dev.mtmux.core.SshClient
import dev.mtmux.core.WarmConnectionPool

private const val RETENTION_KEY = "sshIdleCapacity"
fun savedSshRetention(context: android.content.Context) = SshRetention.fromCapacity(
    context.getSharedPreferences("terminal-settings", 0).getInt(RETENTION_KEY, SshRetention.MEDIUM.capacity))

@Composable fun SshRetentionSettings(pool: WarmConnectionPool<SshClient>) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(savedSshRetention(context)) }
    Text(stringResource(R.string.settings_ssh_retention), color = MaterialTheme.colorScheme.primary)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SshRetention.entries.forEach { option ->
            val label = when (option) {
                SshRetention.NONE -> R.string.ssh_retention_none
                SshRetention.FEW -> R.string.ssh_retention_few
                SshRetention.MEDIUM -> R.string.ssh_retention_medium
                SshRetention.MANY -> R.string.ssh_retention_many
                SshRetention.VERY_MANY -> R.string.ssh_retention_very_many
            }
            FilterChip(selected = selected == option, onClick = {
                selected = option
                context.getSharedPreferences("terminal-settings", 0).edit().putInt(RETENTION_KEY, option.capacity).apply()
                pool.setCapacity(option.capacity)
            }, label = { Text(stringResource(label), textAlign = TextAlign.Center, maxLines = 2,
                style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.weight(1f).testTag("ssh-retention-${option.name}"))
        }
    }
    Text(stringResource(if (selected == SshRetention.NONE) R.string.ssh_retention_disabled else R.string.ssh_retention_enabled,
        selected.capacity), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ssh-retention-help"))
    Text(stringResource(R.string.ssh_retention_help), style = MaterialTheme.typography.bodySmall)
}
