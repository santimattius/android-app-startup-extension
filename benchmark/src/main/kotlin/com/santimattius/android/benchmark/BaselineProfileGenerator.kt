package com.santimattius.android.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Generates the sample app's Baseline Profile: cold start through the first drawn frame. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "com.santimattius.android.sample",
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
    }
}
