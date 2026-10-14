package com.antlr4plus.parsing.security

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GeneratedFileTrustStoreTest : BasePlatformTestCase() {

    fun `test streaming hash matches byte array hash`() {
        val content = ByteArray(32_769) { index -> (index % 251).toByte() }

        assertEquals(
            GeneratedFileTrustStore.computeHash(content),
            ByteArrayInputStream(content).use(GeneratedFileTrustStore::computeHash),
        )
    }

    fun `test syntactic path aliases share one generated entry`() {
        val store = GeneratedFileTrustStore()
        store.markGenerated("/project/generated/../generated/Demo.class", "class-hash")

        assertEquals("class-hash", store.get("/project/generated/Demo.class"))
        assertEquals(1, store.size())
        assertEquals("class-hash", store.remove("/project/generated/./Demo.class"))
        assertEquals(0, store.size())
    }

    fun `test concurrent writers retain independent entries`() {
        val store = GeneratedFileTrustStore()
        val workers = 8
        val entriesPerWorker = 150
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val writes =
                (0 until workers).map { worker ->
                    pool.submit {
                        start.await()
                        repeat(entriesPerWorker) { index ->
                            val path = "/project/generated/$worker/$index.class"
                            val hash = "$worker-$index"
                            store.markGenerated(path, hash)
                            check(store.get(path) == hash)
                        }
                    }
                }
            start.countDown()
            writes.forEach { it.get(10, TimeUnit.SECONDS) }

            assertEquals(workers * entriesPerWorker, store.size())
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
