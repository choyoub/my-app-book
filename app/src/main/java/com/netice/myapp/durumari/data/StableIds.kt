package com.netice.myapp.durumari.data

import java.security.MessageDigest

internal fun stableId(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
