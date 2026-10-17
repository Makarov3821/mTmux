package dev.mtmux

import org.junit.Assert.*
import org.junit.Test

class TaskConnectionTest {
    @Test fun retryRejectsChangedTargetsButAllowsAliasAndCredentialChanges() {
        val profile = ServerProfile(id="selected", name="server", host="example.invalid", port=22,
            user="user", path="tmux", keyAuthentication=false)
        val task = RecentTask(profile.id, profile.host, profile.port, profile.user, profile.path,
            "pin", "123:456:\$1:@2:%3:789", "session", "window")
        for (selection in listOf(TaskConnection(profile, task), TaskConnection(profile, null))) {
            assertTrue(selection.matches(profile.copy(name="renamed", keyAuthentication=true)))
            for (changed in listOf(profile.copy(id="other"), profile.copy(host="other.invalid"),
                profile.copy(port=2222), profile.copy(user="other"), profile.copy(path="/usr/bin/tmux"))) {
                assertFalse(selection.matches(changed))
            }
        }
    }
}
