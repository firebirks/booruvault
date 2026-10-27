package io.github.firebirks.booruvault.net

import okhttp3.internal.publicsuffix.PublicSuffixDatabase

/**
 * On Android OkHttp reads its public suffix list from the app's assets, which a JVM test has no
 * access to. This installs an instance that reads the same list from the test classpath
 * (`test/resources/okhttp3/internal/publicsuffix/PublicSuffixDatabase.list`, a copy of the asset
 * OkHttp 5.4 ships). The classes involved are internal to OkHttp, hence the reflection.
 */
object TestPublicSuffixes {
    val installed: Boolean by lazy {
        val listClass = Class.forName("okhttp3.internal.publicsuffix.ResourcePublicSuffixList")
        val list = listClass.getConstructor().newInstance()
        val database = PublicSuffixDatabase::class.java
            .getConstructor(Class.forName("okhttp3.internal.publicsuffix.PublicSuffixList"))
            .newInstance(list)
        PublicSuffixDatabase::class.java.getMethod("access\$setInstance\$cp", PublicSuffixDatabase::class.java).invoke(null, database)
        true
    }
}
