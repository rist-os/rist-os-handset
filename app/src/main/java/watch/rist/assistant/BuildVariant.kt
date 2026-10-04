package watch.rist.assistant

import java.io.File

/** Written by aosp/rist.mk only when RIST_PUBLIC_BUILD=true; an app pushed onto an older image sees a dev build. */
object BuildVariant {

    internal const val PUBLIC_MARKER = "/product/etc/rist/public-build"

    @Volatile internal var override: Boolean? = null

    private val marker: Boolean by lazy { runCatching { File(PUBLIC_MARKER).isFile }.getOrDefault(false) }

    fun isPublic(): Boolean = override ?: marker
}
