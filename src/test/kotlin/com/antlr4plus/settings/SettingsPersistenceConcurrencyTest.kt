package com.antlr4plus.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotEquals

class SettingsPersistenceConcurrencyTest : BasePlatformTestCase() {

    fun `test persistence snapshot changes when any tracked setting changes`() {
        val service = project.getService(SettingsPersistenceService::class.java)
        val liveSettings = LiveSettings(service, "/snapshot-${System.nanoTime()}.g4p")
        try {
            val initial = liveSettings.persistenceSnapshot()

            liveSettings.outputDir = "generated"
            val outputChanged = liveSettings.persistenceSnapshot()
            assertNotEquals(initial, outputChanged)

            liveSettings.outputDir = initial.generation.outputDir
            liveSettings.treeNodeColor = "#123456"
            assertNotEquals(initial, liveSettings.persistenceSnapshot())
        } finally {
            liveSettings.dispose()
        }
    }

    fun `test concurrent settings lookup creates one state per path`() {
        val service = project.getService(SettingsPersistenceService::class.java)
        val workers = 12
        val iterations = 1_000
        val testRoot = "/concurrency-${System.nanoTime()}"
        val start = CountDownLatch(1)
        val sharedStates = ConcurrentLinkedQueue<State>()
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val tasks =
                (0 until workers).map { worker ->
                    executor.submit {
                        start.await()
                        repeat(iterations) { iteration ->
                            sharedStates.add(service.getSettings("$testRoot/shared.g4p"))
                            service.getSettings("$testRoot/$worker-${iteration % 10}.g4p")
                        }
                    }
                }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }

            val createdKeys = service.state.fileData.keys.filter { it.startsWith("$testRoot/") }
            assertEquals(1 + workers * 10, createdKeys.size)
            val identities = Collections.newSetFromMap(IdentityHashMap<State, Boolean>())
            identities.addAll(sharedStates)
            assertEquals(1, identities.size)
            assertSame(
                service.getSettings("$testRoot/shared.g4p"),
                service.getSettings("$testRoot/shared.g4p"),
            )
        } finally {
            executor.shutdownNow()
        }
    }
}
