package com.perfmode.boost.device

import com.perfmode.boost.AppContext
import org.json.JSONObject

data class PlatformEntry(
    val id: String,
    val name: String,
    val vendor: String,
    val boards: List<String>,
    val note: String
)

data class BrandEntry(val id: String, val name: String)

data class DeviceEntry(
    val id: String,
    val brand: String,
    val name: String,
    val platformId: String,
    val alias: List<String>,
    val confidence: String,
    val note: String
)

data class CandidateSetting(
    val ns: String,
    val keys: List<String>,
    val on: String,
    val off: String
)

data class BrandRecipe(
    val brandId: String,
    val entry: String,
    val candidates: List<CandidateSetting>,
    val notes: String
)

data class DeviceMatch(
    val device: DeviceEntry?,
    val platform: PlatformEntry?,
    val kind: MatchKind
)

enum class MatchKind {
    EXACT_MODEL,   // 型号命中机型库
    BY_BOARD,      // 未收录型号，但 board/SoC 识别出芯片
    BY_VENDOR,     // 只识别出芯片厂商
    GENERIC        // 完全未识别，走纯探测通用配方
}

data class ProfileDb(
    val schema: Int,
    val disclaimer: String,
    val platforms: List<PlatformEntry>,
    val brands: List<BrandEntry>,
    val devices: List<DeviceEntry>,
    val recipes: Map<String, BrandRecipe>
)

/** 机型库：只负责"选择"与"路由"，不负责声称某条命令一定有效。 */
object ProfileRepository {

    private var cache: ProfileDb? = null

    fun db(): ProfileDb {
        cache?.let { return it }
        val text = AppContext.get().assets.open("profiles.json").bufferedReader().readText()
        val root = JSONObject(text)

        val platforms = mutableListOf<PlatformEntry>()
        val pa = root.getJSONArray("platforms")
        for (i in 0 until pa.length()) {
            val o = pa.getJSONObject(i)
            platforms += PlatformEntry(
                id = o.getString("id"),
                name = o.getString("name"),
                vendor = o.getString("vendor"),
                boards = o.optJSONArray("boards")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                note = o.optString("note", "")
            )
        }

        val brands = mutableListOf<BrandEntry>()
        val ba = root.getJSONArray("brands")
        for (i in 0 until ba.length()) {
            val o = ba.getJSONObject(i)
            brands += BrandEntry(o.getString("id"), o.getString("name"))
        }

        val devices = mutableListOf<DeviceEntry>()
        val da = root.getJSONArray("devices")
        for (i in 0 until da.length()) {
            val o = da.getJSONObject(i)
            devices += DeviceEntry(
                id = o.getString("id"),
                brand = o.getString("brand"),
                name = o.getString("name"),
                platformId = o.getString("platform"),
                alias = o.optJSONArray("alias")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                confidence = o.optString("confidence", "low"),
                note = o.optString("note", "")
            )
        }

        val recipes = mutableMapOf<String, BrandRecipe>()
        val ra = root.getJSONObject("brandRecipes")
        for (key in ra.keys()) {
            val o = ra.getJSONObject(key)
            val cands = mutableListOf<CandidateSetting>()
            val ca = o.optJSONArray("candidates")
            if (ca != null) {
                for (i in 0 until ca.length()) {
                    val c = ca.getJSONObject(i)
                    val ka = c.getJSONArray("keys")
                    cands += CandidateSetting(
                        ns = c.getString("ns"),
                        keys = (0 until ka.length()).map { ka.getString(it) },
                        on = c.optString("on", "1"),
                        off = c.optString("off", "0")
                    )
                }
            }
            recipes[key] = BrandRecipe(
                brandId = key,
                entry = o.optString("entry", ""),
                candidates = cands,
                notes = o.optString("notes", "")
            )
        }

        val db = ProfileDb(
            schema = root.optInt("schema", 1),
            disclaimer = root.optString("disclaimer", ""),
            platforms = platforms,
            brands = brands,
            devices = devices,
            recipes = recipes
        )
        cache = db
        return db
    }

    fun platforms(): List<PlatformEntry> = db().platforms
    fun brands(): List<BrandEntry> = db().brands
    fun disclaimer(): String = db().disclaimer

    fun devices(brandId: String?, platformId: String?): List<DeviceEntry> =
        db().devices.filter {
            (brandId == null || it.brand == brandId) && (platformId == null || it.platformId == platformId)
        }

    fun recipe(brandId: String): BrandRecipe? = db().recipes[brandId]

    fun platform(id: String): PlatformEntry? = db().platforms.firstOrNull { it.id == id }

    /** 用探测到的真实型号反查机型条目；找不到返回 null，由 UI 走"按芯片选择" */
    fun matchDevice(profile: DeviceProfile): DeviceEntry? {
        val model = profile.model.trim()
        val manufacturer = profile.manufacturer.trim()
        if (model.isBlank()) return null
        val candidates = db().devices.filter { it.alias.isNotEmpty() }
        return candidates.firstOrNull { d ->
            d.alias.any { a ->
                val al = a.trim()
                al.isNotBlank() && (model.equals(al, true) || model.contains(al, true) || al.contains(model, true))
            }
        } ?: db().devices.firstOrNull { d ->
            d.alias.any { manufacturer.contains(it.trim(), true) && it.isNotBlank() } && model.isNotBlank()
        }
    }

    /**
     * 一次解析出「机型 + 平台 + 是否兜底」，UI 据此给用户明确反馈。
     *
     * 兜底优先级：精确机型 → 平台 board 匹配 → SoC 型号匹配 → 厂商兜底 → generic。
     * 走到 generic 也不会失败：执行层本来就只认探测到的可写节点，不认预设。
     */
    fun resolve(profile: DeviceProfile): DeviceMatch {
        val device = matchDevice(profile)
        if (device != null) {
            return DeviceMatch(device, platform(device.platformId), MatchKind.EXACT_MODEL)
        }
        val byBoard = matchPlatform(profile)
        if (byBoard != null && byBoard.id != "generic") {
            return DeviceMatch(null, byBoard, MatchKind.BY_BOARD)
        }
        val fallback = when (profile.socVendor) {
            SocVendor.QTI -> db().platforms.firstOrNull { it.id == "sd870" }?.let {
                DeviceMatch(null, it, MatchKind.BY_VENDOR)
            }
            SocVendor.MTK -> db().platforms.firstOrNull { it.id == "d8000" }?.let {
                DeviceMatch(null, it, MatchKind.BY_VENDOR)
            }
            else -> null
        } ?: DeviceMatch(null, db().platforms.firstOrNull { it.id == "generic" }, MatchKind.GENERIC)
        return fallback
    }

    /**
     * 按探测字段猜平台。命中顺序：board/platform/chipname → SoC model → 厂商。
     */
    fun matchPlatform(profile: DeviceProfile): PlatformEntry? {
        val key = listOf(
            profile.platform, profile.board, profile.chipname,
            profile.hardware, profile.socModel, profile.skuCode
        ).joinToString(" ").lowercase()

        // 1) 精确 board 命中（最长匹配优先，避免 sm8250 被 sm82 之类前缀误伤）
        val hit = db().platforms
            .mapNotNull { p ->
                val best = p.boards
                    .filter { it.length >= 4 && key.contains(it.lowercase()) }
                    .maxByOrNull { it.length }
                if (best != null) p to best.length else null
            }
            .maxByOrNull { it.second }?.first
        if (hit != null) {
            // 865/870 同为 kona、8G2/8G3 同为 kalama：board 无法区分，用最高频辅助判定
            val maxFreq = profile.cpuPolicies.maxOfOrNull { it.hwMaxKhz } ?: 0L
            val refined = when (hit.id) {
                "sd870", "sd865" ->
                    db().platforms.firstOrNull {
                        it.id == if (maxFreq >= 3_000_000L) "sd870" else "sd865"
                    } ?: hit
                "sd8g3", "sd8g2" ->
                    db().platforms.firstOrNull {
                        it.id == if (maxFreq >= 3_250_000L) "sd8g3" else "sd8g2"
                    } ?: hit
                else -> hit
            }
            return refined
        }

        // 2) SoC 型号文本命中（如 "Snapdragon 870"、"天玑 8100"）
        val soc = (profile.socModel + " " + profile.hardware).lowercase()
        val socHit = db().platforms.firstOrNull { p ->
            when (p.id) {
                "sd8e" -> soc.contains("8 elite") || soc.contains("sm8750")
                "sd8g3" -> soc.contains("8 gen 3") || soc.contains("sm8650")
                "sd8g2" -> soc.contains("8 gen 2") || soc.contains("sm8550")
                "sd8g1" -> soc.contains("8 gen 1") || soc.contains("sm8450") || soc.contains("sm8475")
                "sd888" -> soc.contains("888") || soc.contains("sm8350")
                "sd870" -> soc.contains("870") || soc.contains("sm8250")
                "sd865" -> soc.contains("865") || soc.contains("sm8250")
                "d9400" -> soc.contains("9400")
                "d9300" -> soc.contains("9300")
                "d9200" -> soc.contains("9200")
                "d8400" -> soc.contains("8400")
                "d8300" -> soc.contains("8300")
                "d8200" -> soc.contains("8200")
                "d8000" -> soc.contains("8100") || soc.contains("8000")
                "d1200" -> soc.contains("1200") || soc.contains("1100")
                else -> false
            }
        }
        if (socHit != null) return socHit

        // 3) 厂商兜底
        return when (profile.socVendor) {
            SocVendor.QTI -> db().platforms.firstOrNull { it.id == "sd870" }
            SocVendor.MTK -> db().platforms.firstOrNull { it.id == "d8000" }
            else -> db().platforms.firstOrNull { it.id == "generic" }
        }
    }
}
