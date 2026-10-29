package dev.mtmux

import android.content.Context
import dev.mtmux.core.*
import org.json.JSONObject

internal data class StoredDoctorReport(val checkedAt: Long, val report: TmuxDoctorReport)

/** Revision-scoped results never imply the SSH connection itself is still alive. */
internal class TmuxDoctorStore(context: Context) {
    private val prefs = context.getSharedPreferences("tmux-doctor", Context.MODE_PRIVATE)
    fun report(id: String, revision: String): StoredDoctorReport? = runCatching {
        val item = JSONObject(prefs.getString("report:$id", null) ?: return null)
        if (item.getString("revision") != revision) return null
        val facts = item.getJSONObject("facts")
        StoredDoctorReport(item.getLong("time"), TmuxDoctorReport(facts.keys().asSequence().associateWith { facts.getString(it) }))
    }.getOrNull()
    fun save(id: String, revision: String, report: TmuxDoctorReport, detailed: Boolean, now: Long = System.currentTimeMillis()) {
        val entry = JSONObject().put("revision", revision).put("time", now).put("facts", JSONObject(report.facts))
        val availability = JSONObject().put("revision", revision).put("time", now).put("state", report.availability.name)
        check(prefs.edit().also {
            if (detailed) it.putString("report:$id", entry.toString())
            it.putString("availability:$id", availability.toString())
        }.commit())
    }
    fun availability(id: String, revision: String, now: Long): TmuxAvailability = runCatching {
        val item = JSONObject(prefs.getString("availability:$id", null) ?: return TmuxAvailability.UNKNOWN)
        DoctorAvailabilityRecord(item.getString("revision"),item.getLong("time"),TmuxAvailability.valueOf(item.getString("state"))).effective(revision,now)
    }.getOrDefault(TmuxAvailability.UNKNOWN)
    fun blocked(id: String, revision: String, now: Long) = availability(id,revision,now)==TmuxAvailability.UNAVAILABLE
    fun expiresAt(id: String): Long = runCatching { JSONObject(prefs.getString("availability:$id", null) ?: return 0).getLong("time") + TTL }.getOrDefault(0)
    fun receipt(id: String, revision: String): TmuxConfigReceipt? = runCatching {
        val value = JSONObject(prefs.getString("receipt:$id", null) ?: return null)
        if (value.getString("revision") != revision) return null
        TmuxConfigReceipt(value.getString("home"), value.getString("identity"), value.getString("hash"), value.getInt("history"))
    }.getOrNull()
    fun saveReceipt(id: String, revision: String, receipt: TmuxConfigReceipt?) {
        val edit = prefs.edit()
        if (receipt == null) edit.remove("receipt:$id") else edit.putString("receipt:$id", JSONObject()
            .put("revision", revision).put("home", receipt.home).put("identity", receipt.identity).put("hash", receipt.sha256).put("history", receipt.history).toString())
        check(edit.commit())
    }
    companion object { const val TTL = DoctorAvailabilityRecord.TTL }
}
