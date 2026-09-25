package app.melodrift.music.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import coil.compose.AsyncImage
import coil.request.ImageRequest

/*
 * 评论区网易云表情的解析与内嵌渲染。
 *
 * 数据来源（与社区一致）：
 * - 默认表情：官方 PC Web core.js 的 i2q 映射（60 个），图片 emoji_{ID}@2x.png；
 * - 多多 / 西西系列：官方 Android 评论社区 RN 包的 CustomEmojiMap 直链（23 个）；
 * - 其余未收录的 token（无公开图片资源）保持原文，与官方 web 一致；不做 emoji 兜底。
 *
 * 渲染：Compose 没有 <img>，用 AnnotatedString 的 inlineContent（内联占位 + 子组合）
 * 在文本流中原位渲染图片；占位框尺寸 = 正文字号 × 比例，Text 换行 / maxLines /
 * 省略号 / 长按复制等行为全部保留。图片由 Coil 加载并缓存，请求带 music.163.com
 * Referer（与官方客户端一致）；某张图加载失败时该段退回显示 [xxx] 原文，不会留白。
 *
 * 注意：inlineContent 解析用的 tag 是 Compose 的完全限定名
 * androidx.compose.foundation.text.inlineContent，写短名会匹配不到、占位符漏出乱码。
 */

/** 默认表情图片（s3 镜像可直连；@2x 为高清 42x42） */
private const val EMOJI_URL = "https://s3.music.126.net/style/web2/emoji/emoji_%d@2x.png"

/** 请求官方 CDN 时带的 Referer（同官方客户端，防个别节点拦截） */
private const val REFERER = "https://music.163.com/"

/** 表情相对正文字号的比例：官方 web 约 20px / 14px ≈ 1.4 */
private const val EMOJI_SCALE = 1.4f

/** 认识的表情 token 正则（方括号包 1..32 个非括号字符，同社区实现） */
private val EMOJI_PATTERN = Regex("\\[([^\\[\\]\\r\\n]{1,32})\\]")

/** inlineContent 解析用的完全限定 tag（Compose 源码里的 RESOLVE_INLINE_CONTENT） */
private const val INLINE_TAG = "androidx.compose.foundation.text.inlineContent"

/** 表情占位符：对象替换符（仅作 range 占位，真正替换成图片的是 inlineContent） */
private const val EMOJI_PLACEHOLDER = "\uFFFC"

/**
 * 网易云默认表情映射（token → emoji_{ID}@2x.png 的 ID）。
 * 逐字取自官方 web core.js 的 i2q 常量表；token 两边带方括号，如 [大哭] → emoji_357.png。
 */
private val EMOJI_IDS: Map<String, Int> = mapOf(
    "大笑" to 86, "可爱" to 85, "憨笑" to 359, "色" to 95, "亲亲" to 363, "惊恐" to 96,
    "流泪" to 356, "亲" to 362, "呆" to 352, "哀伤" to 342, "呲牙" to 343, "吐舌" to 348,
    "撇嘴" to 353, "怒" to 361, "奸笑" to 341, "汗" to 97, "痛苦" to 346, "惶恐" to 354,
    "生病" to 350, "口罩" to 351, "大哭" to 357, "晕" to 355, "发怒" to 115, "开心" to 360,
    "鬼脸" to 94, "皱眉" to 87, "流感" to 358, "爱心" to 33, "心碎" to 34, "钟情" to 303,
    "星星" to 309, "生气" to 314, "便便" to 89, "强" to 13, "弱" to 372, "拜" to 14,
    "牵手" to 379, "跳舞" to 380, "禁止" to 374, "这边" to 262, "爱意" to 106, "示爱" to 376,
    "嘴唇" to 367, "狗" to 81, "猫" to 78, "猪" to 100, "兔子" to 459, "小鸡" to 450,
    "公鸡" to 461, "幽灵" to 116, "圣诞" to 411, "外星" to 101, "钻石" to 52, "礼物" to 107,
    "男孩" to 0, "女孩" to 1, "蛋糕" to 337, "18" to 186, "圈" to 312, "叉" to 313
)

// 一张表整理自社区对官方数据的复刻（ldx123000/Hydrogen-Music 的 emojiParser.js 等）。

/** 多多 / 西西系列表情直链（官方 Android 评论社区 CustomEmojiMap） */
private val EMOJI_CUSTOM_URLS: Map<String, String> = mapOf(
    "多多大笑" to "https://p1.music.126.net/V4m7SdpzgrPfjJWaTu3xSQ==/109951163626285326.jpg",
    "多多耍酷" to "https://p1.music.126.net/x5mZknOpJNzKC7LS0zv2iA==/109951163626286808.jpg",
    "多多比耶" to "https://p1.music.126.net/BqJoqXngUpIZ3pq_Fvrvbw==/109951163626291112.jpg",
    "多多大哭" to "https://p2.music.126.net/XuQpmBaIzQ6uJ3mtmSBESQ==/109951163626288209.jpg",
    "多多瞌睡" to "https://p1.music.126.net/WfFex7GPSUiuIKE8anlcbA==/109951163626285332.jpg",
    "多多难过" to "https://p2.music.126.net/yaiHLfm4mpIMqu9NaJwnIA==/109951163626282475.jpg",
    "多多笑哭" to "https://p1.music.126.net/UG4mAEogOWZRhjlxBRHDTw==/109951163626295026.jpg",
    "多多可怜" to "https://p1.music.126.net/od41_QdeiOwB2i9Tuk0h-Q==/109951163626289680.jpg",
    "多多无语" to "https://p2.music.126.net/8x10ArHD1deDphGZ-_WfVw==/109951163626291589.jpg",
    "多多捂脸" to "https://p2.music.126.net/3NOXs8vDoAsZWTF_3O0zrQ==/109951163626287335.jpg",
    "多多亲吻" to "https://p1.music.126.net/2zaHfDaWioE7V-CLH7kMQQ==/109951163626285824.jpg",
    "多多调皮" to "https://p2.music.126.net/ZGF1A6bKMuFyW3qXK-tZjw==/109951163626288207.jpg",
    "西西心动" to "https://p1.music.126.net/3-uHZ9cyQNXZh9Bu7e21TQ==/109951163626284860.jpg",
    "西西发怒" to "https://p1.music.126.net/KMCVtbMU2vgjG3SiCBlRNg==/109951163626291586.jpg",
    "西西惊讶" to "https://p2.music.126.net/vegDCq0l-hXeZZLb8ks8qw==/109951163626290613.jpg",
    "西西奸笑" to "https://p2.music.126.net/X8dYyhEH_5O7liwCyOueZg==/109951163626285329.jpg",
    "西西晕了" to "https://p2.music.126.net/WrzdxYPaj-YnKPsjIY0rEw==/109951163626294527.jpg",
    "西西机智" to "https://p1.music.126.net/1zU_MqYm-HVsMZF8kU_xLw==/109951163626295022.jpg",
    "西西惊吓" to "https://p2.music.126.net/pc3WJ0iLPYZLWExXW-UZdQ==/109951163626292571.jpg",
    "西西流汗" to "https://p2.music.126.net/R0iMN1AqBe5hDKWPSO3Dug==/109951163626281959.jpg",
    "西西呕吐" to "https://p1.music.126.net/E4CO8ilH6Q5Pb829Nv0Xvg==/109951163626287760.jpg",
    "西西再见" to "https://p1.music.126.net/Vn3cObjyvp_RT_lNeP4s0g==/109951163626290116.jpg",
    "西西疑问" to "https://p2.music.126.net/QRiFigltORAlbhZfgOqu4w==/109951163626285827.jpg",
)

/** 正文里的一段：普通文本 或 图片表情 */
internal sealed interface EmojiSegment {
    /** 原始字符串（带方括号） */
    val raw: String

    data class Text(override val raw: String) : EmojiSegment

    data class Image(override val raw: String, val url: String) : EmojiSegment
}

/** 把正文切成「文本 / 图片表情」片段；未收录的 token 保持为文本 */
internal fun parseEmojiSegments(text: String): List<EmojiSegment> {
    if (text.isEmpty()) return listOf(EmojiSegment.Text(text))
    val out = ArrayList<EmojiSegment>(2)
    var last = 0
    for (m in EMOJI_PATTERN.findAll(text)) {
        if (m.range.first > last) out.add(EmojiSegment.Text(text.substring(last, m.range.first)))
        val key = m.groupValues.getOrNull(1) ?: ""
        val seg = when {
            EMOJI_IDS.containsKey(key) ->
                EmojiSegment.Image(m.value, EMOJI_URL.format(EMOJI_IDS.getValue(key)))

            EMOJI_CUSTOM_URLS.containsKey(key) ->
                EmojiSegment.Image(m.value, EMOJI_CUSTOM_URLS.getValue(key))

            else -> EmojiSegment.Text(m.value)
        }
        out.add(seg)
        last = m.range.last + 1
    }
    if (last < text.length) out.add(EmojiSegment.Text(text.substring(last)))
    return out
}

/**
 * 渲染带网易云表情的评论文本。
 *
 * 行为和 [Text] 一致（同参往下透传），仅多了表情处理：命中的 [xxx] 用内联占位框 +
 * Coil AsyncImage 原地显示；某张图加载失败时该段退回显示 [xxx] 原文（不留白）。
 */
@Composable
fun EmojiText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    emojiScale: Float = EMOJI_SCALE
) {
    val context = LocalContext.current

    // 表情占位框：正文字号 × 比例（sp 单位）。Text 排版时按 density 换算 px，
    // 与真实字形完全同尺度，density / fontScale 变化自动跟随。
    val fontSizeValue = style.fontSize.takeIf { it.type == TextUnitType.Sp }?.value ?: 14f
    val emojiSize = TextUnit(value = fontSizeValue * emojiScale, type = TextUnitType.Sp)

    val segments = remember(text) { parseEmojiSegments(text) }
    val urls = remember(segments) {
        segments.filterIsInstance<EmojiSegment.Image>().map { it.url }.distinct()
    }

    // 加载失败的图：对应段退回显示原文，不再占位留白
    var failedUrls by remember(segments) { mutableStateOf(emptySet<String>()) }

    val annotated = remember(segments, failedUrls) {
        buildAnnotatedString {
            for (s in segments) {
                when (s) {
                    is EmojiSegment.Text -> append(s.raw)
                    is EmojiSegment.Image ->
                        if (s.url in failedUrls) {
                            append(s.raw)
                        } else {
                            // 注解值 = 图片 URL，即 inlineContent 的 key
                            pushStringAnnotation(INLINE_TAG, s.url)
                            append(EMOJI_PLACEHOLDER)
                            pop()
                        }
                }
            }
        }
    }

    val inlineContent = remember(urls, emojiSize, failedUrls) {
        urls.filterNot { it in failedUrls }.associate { url ->
            url to InlineTextContent(
                placeholder = Placeholder(
                    width = emojiSize,
                    height = emojiSize,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                ),
                children = {
                    // 注意：children 的参数是占位符区间内的文本（即 \uFFFC），不是注解值；
                    // 图片地址必须从闭包捕获的 url 取，不能用回调形参。
                    AsyncImage(
                        // 带 music.163.com Referer，与官方客户端一致
                        model = ImageRequest.Builder(context)
                            .data(url)
                            .setHeader("Referer", REFERER)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        onError = {
                            // 加载失败：退回显示 [xxx] 原文，不留白
                            failedUrls = failedUrls + url
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            )
        }
    }

    Text(
        annotated,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = inlineContent
    )
}