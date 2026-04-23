package com.kaleaon.mnxmindmaker.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityNavHostFragmentTest {

    @Test
    fun requireNavHostFragmentThrowsWhenFragmentIsMissing() {
        val exception = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            MainActivity.requireNavHostFragment(null)
        }

        assertTrue(exception.message.orEmpty().contains("Expected NavHostFragment"))
        assertTrue(exception.message.orEmpty().contains("found null"))
    }

    @Test
    fun requireNavHostFragmentThrowsWhenFragmentTypeDoesNotMatch() {
        val exception = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            MainActivity.requireNavHostFragment(Any())
        }

        assertTrue(exception.message.orEmpty().contains("Expected NavHostFragment"))
        assertTrue(exception.message.orEmpty().contains(Any::class.java.name))
    }
}
