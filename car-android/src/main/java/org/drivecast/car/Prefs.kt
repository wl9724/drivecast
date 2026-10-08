package org.drivecast.car

import android.content.Context
import org.drivecast.protocol.hex
import org.drivecast.protocol.unhex
import java.io.IOException

/** 车机记住的东西：是否开了无线、手机最近用过的 IP、配对过的 iPhone。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("drivecast", Context.MODE_PRIVATE)

    /** phoneId（十六进制）→ "LTK（十六进制） 名称"。和 adbkey 一样明文存在 App 私有目录里。 */
    private val iphones = context.getSharedPreferences("iphones", Context.MODE_PRIVATE)

    var wireless: Boolean
        get() = sp.getBoolean("wireless", false)
        set(v) = sp.edit().putBoolean("wireless", v).apply()

    /** 最近一次连上的 IP 排最前，后面是开启无线时手机报告的地址。 */
    var phoneIps: List<String>
        get() = sp.getString("phone_ips", "").orEmpty().split(',').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("phone_ips", v.distinct().take(8).joinToString(",")).apply()

    fun iphoneLtk(phoneId: ByteArray): ByteArray? =
        iphones.getString(phoneId.hex(), null)?.substringBefore(' ')?.unhex()

    /** commit 而不是 apply：存好了才告诉 iPhone 配对成功。 */
    fun saveIphone(phoneId: ByteArray, ltk: ByteArray, name: String) {
        if (!iphones.edit().putString(phoneId.hex(), "${ltk.hex()} $name").commit()) throw IOException("保存配对失败")
    }

    fun clearIphones() {
        iphones.edit().clear().commit()
    }
}
