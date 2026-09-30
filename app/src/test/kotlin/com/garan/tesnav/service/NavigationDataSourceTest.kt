package com.garan.tesnav.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationDataSourceTest {
    @Test fun `keeps working sources separate from reserved provider interfaces`() {
        assertEquals("来源：高德 API", NavigationDataSource.AMAP_API.buttonText)
        assertEquals("来源：高德车机", NavigationDataSource.AMAP_AUTO.buttonText)
        assertTrue(NavigationDataSource.AMAP_API.integrated)
        assertTrue(NavigationDataSource.AMAP_AUTO.integrated)
        assertFalse(NavigationDataSource.TENCENT_API.integrated)
        assertFalse(NavigationDataSource.GOOGLE_API.integrated)
        assertEquals("腾讯 API（待配置）", NavigationDataSource.TENCENT_API.menuText)
    }
}
