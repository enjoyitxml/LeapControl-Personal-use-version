package com.leapcontrol.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 车模元数据解析（`carpicture` 接口）。
 *
 * 用户反馈：「Lafa5 车型被识别成 B05，拉不出车模」。
 *
 * 拉不出车模这条查到的真问题在这里：解析器原来**先找 2D 定妆照的 URL**，
 * 找不到就整条返回 null —— 而 3D 包的 `h5Key` 就在同一份 data 里。对 Lafa5
 * 这种服务端只下发 3D 那一套的车型，2D 图字段是空的，于是 h5Key 永远拿不到，
 * 首页只能退回 2D（设置页写着「官方没有给这台车下发 3D 模型」）。
 */
class CarModelMetaTest {

    @Test
    fun `只有 h5Key 没有 2D 图时也要解析出来`() {
        val data = JSONObject(
            """
            {"h5Key":"H5_LAFA5","srcKey":"SRC_LAFA5","modelParam":{"carType":"B05","year":2026}}
            """.trimIndent()
        )
        val meta = parseVehiclePictureMeta(data)
        assertNotNull("有 h5Key 就该带出去，否则 3D 永远拉不起来", meta)
        assertEquals("H5_LAFA5", meta!!.h5Key)
        assertEquals("SRC_LAFA5", meta.srcKey)
        assertEquals("B05", meta.modelParam?.optString("carType"))
    }

    @Test
    fun `两者都没有才返回空`() {
        assertNull(parseVehiclePictureMeta(JSONObject("{}")))
        assertNull(
            parseVehiclePictureMeta(JSONObject("""{"h5Key":"","srcKey":"","url":""}"""))
        )
    }

    @Test
    fun `老车型照旧：有 2D 图就有元数据`() {
        val data = JSONObject("""{"shareBindUrl":"http://x/y.png","key":"PIC1"}""")
        val meta = parseVehiclePictureMeta(data)
        assertNotNull(meta)
        assertEquals("PIC1", meta!!.pictureKey)
    }

    @Test
    fun `图片地址的 http 统一升级成 https`() {
        val meta = parseVehiclePictureMeta(JSONObject("""{"imageUrl":"http://a/b.png"}"""))
        assertEquals("https://a/b.png", meta!!.shareBindUrl)
    }

    @Test
    fun `只有 3D 包时图片地址是空串而不是 null`() {
        // 2D 那条链路会自己判空退回；这里给 null 会让下游 NPE
        val meta = parseVehiclePictureMeta(JSONObject("""{"h5Key":"H5"}"""))
        assertEquals("", meta!!.shareBindUrl)
    }

    // ------------------------------------------------------------ 车名展示

    @Test
    fun `我的页车型行不出现代号`() {
        assertEquals("零跑 Lafa5", VehicleHomeStatus.formatVehicleModel("B05"))
        assertEquals("零跑Lafa5", VehicleHomeStatus.formatVehicleModel("零跑B05"))
        // 其它车型的格式保持不变（昵称比对依赖这个字符串）
        assertEquals("零跑 C11", VehicleHomeStatus.formatVehicleModel("C11"))
        assertEquals("零跑C11", VehicleHomeStatus.formatVehicleModel("零跑C11"))
        assertEquals("零跑", VehicleHomeStatus.formatVehicleModel(""))
    }
}
