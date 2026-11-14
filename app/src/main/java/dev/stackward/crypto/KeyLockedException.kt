package dev.stackward.crypto

/** The SSH key needs a fresh fingerprint or password before it can sign. Never retried automatically. */
class KeyLockedException(message: String = "SSH key is locked — confirm with fingerprint or password") : Exception(message)
