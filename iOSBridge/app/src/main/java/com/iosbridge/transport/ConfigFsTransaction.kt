package com.iosbridge.transport

/**
 * Auditable transaction model for a future ConfigFS executor.
 * This class deliberately performs no filesystem or shell operations.
 */
object ConfigFsTransaction {
    const val JOURNAL_VERSION = 1
    enum class Phase { PREPARED, APPLYING, APPLIED, ROLLING_BACK, ROLLED_BACK, FAILED }
    enum class StepKind { CREATE_DIRECTORY, WRITE_ATTRIBUTE, CREATE_SYMLINK }
    enum class StepState { PENDING, APPLIED, ROLLED_BACK, FAILED }

    data class Step(
        val id: String,
        val kind: StepKind,
        val path: String,
        val value: String? = null,
        val expectedBefore: String? = null,
        val state: StepState = StepState.PENDING,
        val observedAfter: String? = null
    )
    data class Journal(
        val version: Int = JOURNAL_VERSION,
        val transactionId: String,
        val ownerMarker: String,
        val createdAtEpochMs: Long,
        val phase: Phase,
        val steps: List<Step>,
        val failure: String? = null
    )
    data class RollbackCheck(val allowed: Boolean, val reason: String)

    fun prepare(transactionId: String, ownerMarker: String, now: Long, steps: List<Step>): Journal {
        require(ID.matches(transactionId)) { "Invalid transaction id" }
        require(MARKER.matches(ownerMarker)) { "Invalid ownership marker" }
        require(steps.isNotEmpty()) { "Transaction must contain steps" }
        require(steps.map { it.id }.toSet().size == steps.size) { "Duplicate step id" }
        steps.forEach {
            require(ID.matches(it.id)) { "Invalid step id" }
            require(it.path.startsWith("/config/usb_gadget/")) { "Path outside ConfigFS gadget root" }
            require(".." !in it.path.split('/')) { "Path traversal rejected" }
            require(it.state == StepState.PENDING && it.observedAfter == null) { "Prepared steps must be pending" }
        }
        return Journal(transactionId = transactionId, ownerMarker = ownerMarker,
            createdAtEpochMs = now, phase = Phase.PREPARED, steps = steps.toList())
    }

    fun begin(journal: Journal): Journal {
        require(journal.phase == Phase.PREPARED) { "Only a prepared transaction can begin" }
        return journal.copy(phase = Phase.APPLYING)
    }

    fun recordApplied(journal: Journal, stepId: String, observedAfter: String): Journal {
        require(journal.phase == Phase.APPLYING) { "Transaction is not applying" }
        require(observedAfter.isNotBlank()) { "Observed state is required" }
        val index = journal.steps.indexOfFirst { it.id == stepId }
        require(index >= 0) { "Unknown step" }
        require(journal.steps[index].state == StepState.PENDING) { "Step is not pending" }
        val updated = journal.steps.toMutableList()
        updated[index] = updated[index].copy(state = StepState.APPLIED, observedAfter = observedAfter)
        val allDone = updated.all { it.state == StepState.APPLIED }
        return journal.copy(steps = updated, phase = if (allDone) Phase.APPLIED else Phase.APPLYING)
    }

    fun recordFailure(journal: Journal, message: String): Journal {
        require(journal.phase == Phase.APPLYING || journal.phase == Phase.PREPARED)
        require(message.isNotBlank())
        return journal.copy(phase = Phase.FAILED, failure = message)
    }

    /** Deletion is permitted only for an object recorded as created by this transaction,
     * with its current state unchanged and no external references/children. */
    fun canRollback(step: Step, currentFingerprint: String?, isEmpty: Boolean,
                    ownerMarkerMatches: Boolean, externallyReferenced: Boolean): RollbackCheck {
        if (step.state != StepState.APPLIED) return RollbackCheck(false, "Step was not recorded as applied")
        if (!ownerMarkerMatches) return RollbackCheck(false, "Ownership marker mismatch")
        if (currentFingerprint == null || currentFingerprint != step.observedAfter)
            return RollbackCheck(false, "Current state differs from transaction result")
        if (externallyReferenced) return RollbackCheck(false, "Object has external references")
        if (step.kind != StepKind.WRITE_ATTRIBUTE && !isEmpty)
            return RollbackCheck(false, "Object is not empty; refusing removal")
        return RollbackCheck(true, "Rollback may be considered after independent review")
    }

    fun beginRollback(journal: Journal): Journal {
        require(journal.phase == Phase.APPLIED || journal.phase == Phase.FAILED || journal.phase == Phase.APPLYING)
        return journal.copy(phase = Phase.ROLLING_BACK)
    }

    fun recordRolledBack(journal: Journal, stepId: String): Journal {
        require(journal.phase == Phase.ROLLING_BACK)
        val index = journal.steps.indexOfFirst { it.id == stepId }
        require(index >= 0)
        val updated = journal.steps.toMutableList()
        require(updated[index].state == StepState.APPLIED)
        updated[index] = updated[index].copy(state = StepState.ROLLED_BACK)
        return journal.copy(steps = updated,
            phase = if (updated.none { it.state == StepState.APPLIED }) Phase.ROLLED_BACK else Phase.ROLLING_BACK)
    }

    /** Stable, line-oriented export. It is a report format, not an executable shell script. */
    fun render(journal: Journal): String = buildString {
        appendLine("IOSBRIDGE-CONFIGFS-JOURNAL/$JOURNAL_VERSION")
        appendLine("transaction=${safe(journal.transactionId)}")
        appendLine("owner=${safe(journal.ownerMarker)}")
        appendLine("created=${journal.createdAtEpochMs}")
        appendLine("phase=${journal.phase}")
        appendLine("failure=${safe(journal.failure ?: "")}")
        journal.steps.forEach { s ->
            appendLine("step=${safe(s.id)}|${s.kind}|${safe(s.path)}|${safe(s.value ?: "")}|${safe(s.expectedBefore ?: "")}|${s.state}|${safe(s.observedAfter ?: "")}")
        }
        appendLine("NOTE=Audit record only; never execute this file as a script.")
    }

    private fun safe(value: String): String = value.replace("\\", "\\\\")
        .replace("\n", "\\n").replace("\r", "\\r").replace("|", "\\p")
    private val ID = Regex("^[A-Za-z0-9_-]{1,48}$")
    private val MARKER = Regex("^[A-Za-z0-9:_-]{8,96}$")
}