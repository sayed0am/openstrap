package org.openstrap.app.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withTimeout

/** How long [endMain] waits for a test's coroutines to finish cancelling. */
private const val END_MAIN_TIMEOUT_MS = 10_000L

/**
 * The end of a test that set Main to a test dispatcher: cancels [jobs] (view model scopes, collectors, a settings
 * scope) and waits for each before [Dispatchers.resetMain]. A hop to Dispatchers.IO still running when the test ends
 * (a settings write, a file read) then resumes while Main is still the test's. Cancelling without the wait let it
 * resume after the reset, and the next test that called runTest failed with UncaughtExceptionsBeforeTest (2026-11-04).
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun endMain(jobs: Iterable<Job?>) {
    try {
        runBlocking { withTimeout(END_MAIN_TIMEOUT_MS) { jobs.filterNotNull().forEach { it.cancelAndJoin() } } }
    } finally {
        Dispatchers.resetMain()
    }
}

/** [endMain] for a few jobs. */
fun endMain(vararg jobs: Job?) = endMain(jobs.asIterable())
