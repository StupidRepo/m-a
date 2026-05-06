package com.vayunmathur.library.util

import android.content.Intent
import android.net.Uri

object IntentHelper {
    /**
     * Extracts file URIs from an intent (ACTION_VIEW or ACTION_SEND).
     */
    fun getUrisFromIntent(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uris.add(it) }
            }
            Intent.ACTION_SEND -> {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }
        }

