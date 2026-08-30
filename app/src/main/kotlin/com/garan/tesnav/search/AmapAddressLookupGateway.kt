package com.garan.tesnav.search

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.geocoder.GeocodeQuery
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.geocoder.RegeocodeResult

/** Thin lifecycle-safe adapter around the Search SDK already bundled with the navigation SDK. */
class AmapAddressLookupGateway(context: Context) : AddressLookupGateway {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val activeSearches = mutableSetOf<GeocodeSearch>()

    @Volatile
    private var closed = false

    override fun searchDestination(
        query: String,
        callback: (LookupResult<List<AddressCandidate>>) -> Unit,
    ) {
        val search = createSearch { callback(LookupResult.Failure("目的地搜索失败：$it")) } ?: return
        search.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
            override fun onGeocodeSearched(result: GeocodeResult?, errorCode: Int) {
                val lookupResult = if (errorCode == AMapException.CODE_AMAP_SUCCESS) {
                    val candidates = result?.geocodeAddressList.orEmpty().mapNotNull { address ->
                        val point = address?.latLonPoint ?: return@mapNotNull null
                        AddressCandidate(
                            latitude = point.latitude,
                            longitude = point.longitude,
                            formattedAddress = address.formatAddress?.trim().orEmpty().ifBlank { query },
                        )
                    }
                    LookupResult.Success(candidates)
                } else {
                    LookupResult.Failure("目的地搜索失败：${AmapSearchErrorMessage.forCode(errorCode)}")
                }
                finish(search) { callback(lookupResult) }
            }

            override fun onRegeocodeSearched(result: RegeocodeResult?, errorCode: Int) = Unit
        })
        runCatching { search.getFromLocationNameAsyn(GeocodeQuery(query, "")) }
            .onFailure { error ->
                finish(search) {
                    callback(LookupResult.Failure("目的地搜索失败：${error.message ?: "无法启动地址搜索"}"))
                }
            }
    }

    override fun reverseGeocode(
        point: AddressPoint,
        callback: (LookupResult<String>) -> Unit,
    ) {
        val search = createSearch { callback(LookupResult.Failure("当前地址解析失败：$it")) } ?: return
        search.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
            override fun onGeocodeSearched(result: GeocodeResult?, errorCode: Int) = Unit

            override fun onRegeocodeSearched(result: RegeocodeResult?, errorCode: Int) {
                val lookupResult = if (errorCode == AMapException.CODE_AMAP_SUCCESS) {
                    result?.regeocodeAddress?.formatAddress
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let { LookupResult.Success(it) }
                        ?: LookupResult.Failure("当前地址解析失败：高德未返回地址")
                } else {
                    LookupResult.Failure("当前地址解析失败：${AmapSearchErrorMessage.forCode(errorCode)}")
                }
                finish(search) { callback(lookupResult) }
            }
        })
        runCatching {
            search.getFromLocationAsyn(
                RegeocodeQuery(
                    LatLonPoint(point.latitude, point.longitude),
                    REVERSE_RADIUS_METERS,
                    GeocodeSearch.AMAP,
                ),
            )
        }.onFailure { error ->
            finish(search) {
                callback(LookupResult.Failure("当前地址解析失败：${error.message ?: "无法启动地址解析"}"))
            }
        }
    }

    override fun close() {
        val searches = synchronized(lock) {
            if (closed) return
            closed = true
            activeSearches.toList().also { activeSearches.clear() }
        }
        searches.forEach { it.setOnGeocodeSearchListener(null) }
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun createSearch(onFailure: (String) -> Unit): GeocodeSearch? {
        if (closed) return null
        val search = try {
            GeocodeSearch(applicationContext)
        } catch (error: AMapException) {
            deliver { onFailure(AmapSearchErrorMessage.forCode(error.errorCode)) }
            return null
        } catch (error: RuntimeException) {
            deliver { onFailure(error.message ?: "高德地址服务初始化失败") }
            return null
        }
        synchronized(lock) {
            if (closed) {
                search.setOnGeocodeSearchListener(null)
                return null
            }
            activeSearches += search
        }
        return search
    }

    private fun finish(search: GeocodeSearch, callback: () -> Unit) {
        search.setOnGeocodeSearchListener(null)
        synchronized(lock) { activeSearches -= search }
        deliver(callback)
    }

    private fun deliver(callback: () -> Unit) {
        val guarded = Runnable { if (!closed) callback() }
        if (Looper.myLooper() == Looper.getMainLooper()) guarded.run() else mainHandler.post(guarded)
    }

    private companion object {
        const val REVERSE_RADIUS_METERS = 200f
    }
}

internal object AmapSearchErrorMessage {
    fun forCode(errorCode: Int): String = when (errorCode) {
        1002, 1008, 1009 -> "高德 API Key、签名或平台配置错误（$errorCode）"
        1005 -> "高德地址服务请求过于频繁（$errorCode）"
        1802, 1804, 1806 -> "高德地址服务网络异常（$errorCode）"
        1200, 1901 -> "地址查询参数无效（$errorCode）"
        else -> "高德地址服务失败（$errorCode）"
    }
}
