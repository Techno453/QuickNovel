package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.util.Apis
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fork reads raw CJK sources and translates them itself, so machine-translation (MTL)
 * aggregators are not registered (docs/sources.md, "Removed sources").
 */
class RemovedSourcesTest {
    private val mtlHosts = listOf(
        "lnmtl.com", "mtlnovel", "sonicmtl.com", "wtr-lab.com", "wuxiabox.com", "fanmtl.com", "wuxia.click",
        "ranovel.com", "meionovels.com", "novelbuddy", "nobadnovel.com", "pawread.com",
    )

    @Test
    fun mtlAggregatorsAreNotRegistered() {
        val registered = Apis.apis.filter { api -> mtlHosts.any { it in api.mainUrl } }
        assertTrue("MTL aggregators registered: ${registered.map { it.name }}", registered.isEmpty())
    }
}
