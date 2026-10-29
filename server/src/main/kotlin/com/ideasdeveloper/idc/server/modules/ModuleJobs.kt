package com.ideasdeveloper.idc.server.modules

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Periodic work of a module (polling an external API, sending scheduled emails...). The server runs
 * it for every active company that has the module enabled, about every [interval] (checked once a
 * minute, so intervals below one minute behave as one minute). Jobs run one after another on a
 * single background thread: keep each run short and bounded (process a batch, continue next time).
 */
class ModuleJob(
    // Unique within the module, e.g. "welcome-emails".
    val id: String,
    val interval: Duration,
    val run: (ModuleJobContext) -> Unit,
)

// What a job receives: the company it runs for, its tenant database (closed after the run) and services.
class ModuleJobContext(
    val companyCode: String,
    val tenant: Connection,
    val access: ModuleAccessService,
)

/**
 * Decides which jobs are due. A job runs on the first tick after startup and then when [ModuleJob.interval]
 * has passed since its last run for that company; a failed run counts as a run, so it is retried at the
 * next interval instead of every minute.
 */
internal class ModuleJobClock {
    private val lastRuns = ConcurrentHashMap<String, Instant>()

    fun isDue(moduleId: String, job: ModuleJob, companyCode: String, now: Instant): Boolean {
        val last = lastRuns[key(moduleId, job, companyCode)] ?: return true
        return Duration.between(last, now) >= job.interval
    }

    fun ran(moduleId: String, job: ModuleJob, companyCode: String, at: Instant) {
        lastRuns[key(moduleId, job, companyCode)] = at
    }

    private fun key(moduleId: String, job: ModuleJob, companyCode: String) = "$moduleId/${job.id}/$companyCode"
}

class ModuleJobScheduler(
    private val registry: ModuleRegistry,
    private val access: ModuleAccessService,
    private val tick: Duration = Duration.ofMinutes(1),
) {
    private val log = LoggerFactory.getLogger(ModuleJobScheduler::class.java)
    private val clock = ModuleJobClock()
    private var executor: ScheduledExecutorService? = null

    fun start() {
        if (executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "module-jobs").apply { isDaemon = true } }.also {
            // The first tick waits a little so startup work (migrations) goes first.
            it.scheduleWithFixedDelay(::runDueJobs, 30, tick.seconds, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    internal fun runDueJobs() {
        try {
            registry.installedModuleIds().sorted().forEach { moduleId ->
                val jobs = registry.module(moduleId)?.jobs.orEmpty()
                if (jobs.isEmpty()) return@forEach
                access.companiesWithModule(moduleId).forEach { companyCode -> runForCompany(moduleId, jobs, companyCode) }
            }
        } catch (failure: Exception) {
            // Never let an error stop the scheduler thread.
            log.error("Module jobs tick failed", failure)
        }
    }

    private fun runForCompany(moduleId: String, jobs: List<ModuleJob>, companyCode: String) {
        val now = Instant.now()
        jobs.filter { clock.isDue(moduleId, it, companyCode, now) }.forEach { job ->
            clock.ran(moduleId, job, companyCode, now)
            try {
                access.tenantConnectionForCompany(companyCode)?.use { tenant -> job.run(ModuleJobContext(companyCode, tenant, access)) }
            } catch (failure: Exception) {
                log.error("Job {} of module {} failed for company {}", job.id, moduleId, companyCode, failure)
            }
        }
    }
}
