package com.leapcontrol.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leapcontrol.app.PERSONAL_EDITION

/**
 * 使用范围与禁止传播声明 —— 登录页、「我的」页共用这一份文案。
 *
 * ## 为什么单独放一份
 * 这种话只要在两处各抄一遍，早晚会改一处漏一处（本项目在车型别名、默认值上都吃过这个亏）。
 * 排版各页自己决定：登录页是星空上半透明的提示块，「我的」页用 [UsageNoticeCard] 这张卡片。
 *
 * ## 口径（两种版本不同）
 * 自用版：第一句是事实陈述（基于开源、无商业元素、只给内部好友自用）；
 * 第二句是责任划分（禁止公开传播，风险由传播者承担）。**两句都要在**，别只留前半句 ——
 * 只写「请勿外传」是请求，写上责任归属才是界线。
 *
 * 个人学习版（[PERSONAL_EDITION]）：这个包本身就是**公开**发布的，
 * 「禁止传播」与事实直接矛盾，所以整段按用户 2026-11-15 的要求换成
 * 「仅限个人学习研究使用，请在学习研究后及时卸载」；第二句从「禁止传播」改成
 * 「非官方、无授权、后果自负」，仍然守住「事实 + 责任」两条。
 */
internal val USAGE_NOTICE_LINES: List<String> = if (PERSONAL_EDITION) {
    listOf(
        "本软件仅限个人学习研究使用，请在学习研究后及时卸载。",
        "本软件为非官方第三方工具，与零跑汽车无任何隶属、授权或合作关系；" +
            "使用本软件所产生的一切后果，均由使用者自行承担。",
    )
} else {
    listOf(
        "本软件基于开源代码二次开发实现，不含任何商业元素，仅提供给内部好友交流学习与自用体验。",
        "严禁在任何公开社交平台、论坛、网盘、群组等渠道传播、转载、分享或售卖。" +
            "因擅自传播、二次分发所引发的一切法律风险与后果，均由传播者自行承担，与开发者无关。",
    )
}

/**
 * 「我的」页页脚的声明卡片 —— **与上面那七条入口同一款卡片**。
 *
 * 这段声明改过四版（都在 2026-11-09），每一版都是被用户看着截图打回来的，别再往回翻：
 *
 *  1. 「关于」页末尾一行灰字；
 *  2. 主题色底 + 描边 + 强调色小标题的强调卡片，整块挪到「我的」页底部 ——
 *     底色比所有功能条目都重，一进页面先看到它，比入口还抢视野；
 *  3. 去掉底色和边框改成页脚浅灰小字，但顺手删了标题又改成居中 —— 四行小字每行末尾参差，
 *     用户打回「太丑了」；
 *  4. 现在是用户要的最终形态：**就是一张普通卡片**。
 *
 * 所以这里的底座参数不是随便定的，必须跟 [ProfileItem] 对齐：
 * [AppColors.panelBg] 底色、14dp 圆角、水平 15dp 内边距；标题 14sp Medium（和其他卡片的标题同款），
 * 正文 11sp、次要文字色（和它们的副标题同款）。
 * 别给它加描边、强调色、emoji 或任何「强调」手法 —— 用户要的是「和上面统一」，不是更显眼。
 */
@Composable
internal fun UsageNoticeCard(modifier: Modifier = Modifier) {
    val c = LocalAppColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.panelBg, RoundedCornerShape(14.dp))
            .padding(horizontal = 15.dp, vertical = 13.dp),
    ) {
        Text(
            "使用范围",
            color = c.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        USAGE_NOTICE_LINES.forEachIndexed { index, line ->
            if (index > 0) Spacer(Modifier.height(6.dp))
            Text(
                text = line,
                modifier = Modifier.fillMaxWidth(),
                color = c.textSecondary,
                fontSize = 11.sp,
                // 长段落必须显式给行高：11sp 配 17sp，密一点才像卡片里的说明文字
                lineHeight = 17.sp,
            )
        }
    }
}
