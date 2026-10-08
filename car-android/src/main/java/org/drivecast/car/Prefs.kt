package org.drivecast.car

import android.content.Context

/** 车机记住的东西：是否开了无线、手机最近用过的 IP。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("drivecast", Context.MODE_PRIVATE)

    var wireless: Boolean
        get() = sp.getBoolean("wireless", false)
        set(v) = sp.edit().putBoolean("wireless", v).apply()

    /** 最近一次连上的 IP 排最前，后面是开启无线时手机报告的地址。 */
    var phoneIps: List<String>
        get() = sp.getString("phone_ips", "").orEmpty().split(',').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("phone_ips", v.distinct().take(8).joinToString(",")).apply()
}
