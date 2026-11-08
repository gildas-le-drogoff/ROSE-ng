package dev.narayan.rose.filejob

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class FileOperationError(
    val path: String,
    val fileName: String,
    val reason: String
)

data class JobResult(
    val jobId: String,
    val job: FileJob,
    val success: Boolean,
    val error: String? = null,
    val failedItems: List<FileOperationError> = emptyList(),
    val createdPaths: List<String> = emptyList()
)

object JobManager {
    private val _activeJobs = MutableStateFlow<Map<String, FileJob>>(emptyMap())
    val activeJobs: StateFlow<Map<String, FileJob>> = _activeJobs.asStateFlow()

    private val _jobEvents = MutableSharedFlow<JobResult>(extraBufferCapacity = 16)
    val jobEvents: SharedFlow<JobResult> = _jobEvents.asSharedFlow()

    private val cancelledJobs = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val completedJobs = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val pausedJobs = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val pauseLock = Object()

    fun cancelJob(jobId: String) {
        cancelledJobs.add(jobId)
        completedJobs.add(jobId)
        pausedJobs.remove(jobId)
        synchronized(pauseLock) {
            pauseLock.notifyAll()
        }
        _activeJobs.update { current -> current - jobId }
    }

    fun isCancelled(jobId: String): Boolean = cancelledJobs.contains(jobId)
    fun isCompleted(jobId: String): Boolean = completedJobs.contains(jobId)

    fun pauseJob(jobId: String) {
        pausedJobs.add(jobId)
        _activeJobs.update { current ->
            val job = current[jobId] ?: return@update current
            current + (jobId to job.copy(isPaused = true))
        }
    }

    fun resumeJob(jobId: String) {
        pausedJobs.remove(jobId)
        synchronized(pauseLock) {
            pauseLock.notifyAll()
        }
        _activeJobs.update { current ->
            val job = current[jobId] ?: return@update current
            current + (jobId to job.copy(isPaused = false))
        }
    }

    fun isPaused(jobId: String): Boolean = pausedJobs.contains(jobId)

    @Throws(java.io.InterruptedIOException::class)
    fun checkWaitIfPaused(jobId: String) {
        while (isPaused(jobId)) {
            if (isCancelled(jobId)) {
                throw java.io.InterruptedIOException("Job cancelled while paused")
            }
            synchronized(pauseLock) {
                if (isPaused(jobId) && !isCancelled(jobId)) {
                    try {
                        pauseLock.wait(200)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw java.io.InterruptedIOException("Job wait interrupted")
                    }
                }
            }
        }
        if (isCancelled(jobId)) {
            throw java.io.InterruptedIOException("Job cancelled")
        }
    }

    fun updateJob(job: FileJob) {
        if (cancelledJobs.contains(job.id) || completedJobs.contains(job.id)) return

        _activeJobs.update { current ->
            if (current.containsKey(job.id) || !completedJobs.contains(job.id)) {
                current + (job.id to job.copy())
            } else {
                current
            }
        }
    }

    fun removeJob(jobId: String) {
        completedJobs.add(jobId)
        _activeJobs.update { current -> current - jobId }
        cancelledJobs.remove(jobId)
    }

    fun completeJob(job: FileJob, success: Boolean, error: String? = null) {
        val cancelled = isCancelled(job.id)
        val finalSuccess = success && !cancelled
        val finalError = if (cancelled && error == null) "Cancelled" else error
        completedJobs.add(job.id)
        val failed = synchronized(job.failedItems) { job.failedItems.toList() }
        val created = synchronized(job.createdPaths) { job.createdPaths.toList() }
        _jobEvents.tryEmit(JobResult(job.id, job.copy(), finalSuccess, finalError, failed, created))
        removeJob(job.id)
    }
}