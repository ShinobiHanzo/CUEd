package dev.cued.core

/** Shared constants for the CUEd core library. */
object CuedCore {
    const val SHARE_SCHEME = "cued"
    /** The download page; a share encoded as `<base>#<query>` opens the page on phones without CUEd and the app on phones with it. */
    const val SHARE_WEB_BASE = "https://shinobihanzo.github.io/CUEd/"
    const val SHARE_PAYLOAD_VERSION = 1
}
