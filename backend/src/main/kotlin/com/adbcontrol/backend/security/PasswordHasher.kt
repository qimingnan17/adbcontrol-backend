package com.adbcontrol.backend.security

import org.mindrot.jbcrypt.BCrypt

object PasswordHasher {
    fun hash(plain: String, rounds: Int = 12): String = BCrypt.hashpw(plain, BCrypt.gensalt(rounds))
    fun verify(plain: String, hash: String): Boolean = runCatching { BCrypt.checkpw(plain, hash) }.getOrElse { false }
}
