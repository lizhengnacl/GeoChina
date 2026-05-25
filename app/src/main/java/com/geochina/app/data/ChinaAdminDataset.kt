package com.geochina.app.data

import android.content.Context
import android.icu.text.Transliterator
import com.geochina.app.model.AdminLevel
import com.geochina.app.model.AdministrativeRegion
import com.geochina.app.model.GeoPoint
import com.geochina.app.model.GeoRect
import com.geochina.app.model.RegionStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

object ChinaAdminDataset {
    private const val PROVINCE_ASSET = "mapdata/province.geojson"
    private const val CITY_ASSET = "mapdata/city.geojson"
    private const val COUNTY_ASSET = "mapdata/county.geojson"
    private const val REGION_FACTS_ASSET = "mapdata/region_facts.json"

    private val pinyinTransliterator: Transliterator by lazy {
        Transliterator.getInstance("Han-Latin/Names; Latin-ASCII")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableLoadVersion = MutableStateFlow(0)

    @Volatile
    private var data: Dataset? = null

    @Volatile
    private var preloadStarted = false

    val loadVersion: StateFlow<Int> = mutableLoadVersion.asStateFlow()

    val regions: List<AdministrativeRegion>
        get() = requireData().regions

    val worldBounds: GeoRect
        get() = requireData().worldBounds

    val auxiliaryPolygons: List<List<GeoPoint>>
        get() = requireData().auxiliaryPolygons

    fun initialize(context: Context) {
        if (data != null) return
        synchronized(this) {
            if (data == null) {
                data = loadProvinceDataset(context.applicationContext)
                mutableLoadVersion.value += 1
            }
        }
        preloadDetails(context.applicationContext)
    }

    fun region(code: String): AdministrativeRegion? = requireData().byCode[code]

    fun regionForLevel(code: String, level: AdminLevel): AdministrativeRegion? =
        requireData().byLevel[level].orEmpty().firstOrNull { it.code == code }

    fun regionsForLevel(level: AdminLevel): List<AdministrativeRegion> = requireData().byLevel[level].orEmpty()

    fun regionsForParent(level: AdminLevel, parentCode: String): List<AdministrativeRegion> =
        requireData().byLevelAndParent[level]?.get(parentCode).orEmpty()

    fun childrenOf(parentCode: String): List<AdministrativeRegion> =
        requireData().childrenByParent[parentCode].orEmpty()

    fun search(query: String, limit: Int = 24): List<AdministrativeRegion> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        val seen = mutableSetOf<String>()
        return regions
            .asSequence()
            .mapNotNull { region ->
                val score = matchScore(region, normalized)
                if (score == Int.MAX_VALUE) null else score to region
            }
            .sortedWith(compareBy<Pair<Int, AdministrativeRegion>> { it.first }.thenBy { it.second.level.ordinal })
            .map { it.second }
            .filter { seen.add(it.code) }
            .take(limit)
            .toList()
    }

    private fun requireData(): Dataset =
        data ?: error("ChinaAdminDataset.initialize(context) must be called before use.")

    private fun preloadDetails(context: Context) {
        if (preloadStarted) return
        synchronized(this) {
            if (preloadStarted) return
            preloadStarted = true
        }
        scope.launch {
            val regionFacts = loadRegionFacts(context)
            val provinceRaw = parseGeoJson(
                json = readAsset(context, PROVINCE_ASSET),
                forcedLevel = AdminLevel.Province,
                includeAuxiliary = true,
            )
            val cityRaw = parseGeoJson(
                json = readAsset(context, CITY_ASSET),
                forcedLevel = AdminLevel.City,
                includeAuxiliary = false,
            )
            data = buildDataset(
                rawRegions = provinceRaw.regions + cityRaw.regions,
                auxiliaryPolygons = provinceRaw.auxiliaryPolygons,
                regionFacts = regionFacts,
            )
            mutableLoadVersion.value += 1

            val countyRaw = parseGeoJson(
                json = readAsset(context, COUNTY_ASSET),
                forcedLevel = AdminLevel.County,
                includeAuxiliary = false,
            )
            data = buildDataset(
                rawRegions = provinceRaw.regions + cityRaw.regions + countyRaw.regions,
                auxiliaryPolygons = provinceRaw.auxiliaryPolygons,
                regionFacts = regionFacts,
            )
            mutableLoadVersion.value += 1
        }
    }

    private fun loadProvinceDataset(context: Context): Dataset {
        val regionFacts = loadRegionFacts(context)
        val provinceRaw = parseGeoJson(
            json = readAsset(context, PROVINCE_ASSET),
            forcedLevel = AdminLevel.Province,
            includeAuxiliary = true,
        )
        return buildDataset(
            rawRegions = provinceRaw.regions,
            auxiliaryPolygons = provinceRaw.auxiliaryPolygons,
            regionFacts = regionFacts,
        )
    }

    private fun buildDataset(
        rawRegions: List<AdministrativeRegion>,
        auxiliaryPolygons: List<List<GeoPoint>>,
        regionFacts: Map<String, RegionFact>,
    ): Dataset {
        val nameByCode = mutableMapOf("100000" to "中国")
        rawRegions.forEach { raw ->
            nameByCode.putIfAbsent(raw.code, raw.name)
        }
        val childrenByParent = rawRegions
            .filter { it.parentCode != null }
            .groupBy { it.parentCode.orEmpty() }
            .mapValues { entry -> entry.value.distinctBy { it.code }.sortedBy { it.code } }
        val areaRankByCode = rankByLevel(rawRegions, regionFacts) { it.areaKm2 }
        val populationRankByCode = rankByLevel(rawRegions, regionFacts) { it.population2020?.toDouble() }

        val regions = rawRegions.map { raw ->
            val children = childrenByParent[raw.code].orEmpty()
            val parentName = nameByCode[raw.parentCode] ?: raw.parentName
            val fact = regionFacts[raw.code]
            raw.copy(
                parentName = parentName,
                childrenCount = children.size,
                subdivisionNames = children.map { it.name },
                history = introductionFor(raw, parentName, children, fact),
                stats = statsFor(raw.level, fact, areaRankByCode[raw.code], populationRankByCode[raw.code]),
            )
        }
        val provincePolygons = rawRegions
            .filter { it.level == AdminLevel.Province }
            .flatMap { it.polygons }
        val bounds = computeBounds(provincePolygons + auxiliaryPolygons)
        return Dataset(
            regions = regions,
            byCode = regions
                .sortedBy { it.level.ordinal }
                .associateBy { it.code },
            byLevel = regions.groupBy { it.level },
            byLevelAndParent = regions
                .filter { it.parentCode != null }
                .groupBy { it.level }
                .mapValues { levelEntry ->
                    levelEntry.value
                        .groupBy { it.parentCode.orEmpty() }
                        .mapValues { parentEntry -> parentEntry.value.sortedBy { it.code } }
            },
            childrenByParent = childrenByParent,
            worldBounds = bounds,
            auxiliaryPolygons = auxiliaryPolygons,
        )
    }

    private fun readAsset(context: Context, name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    private fun parseGeoJson(
        json: String,
        forcedLevel: AdminLevel,
        includeAuxiliary: Boolean,
    ): ParseResult {
        val root = JSONObject(json)
        val features = root.getJSONArray("features")
        val regions = mutableListOf<AdministrativeRegion>()
        val auxiliaryPolygons = mutableListOf<List<GeoPoint>>()
        for (index in 0 until features.length()) {
            val feature = features.getJSONObject(index)
            val properties = feature.optJSONObject("properties") ?: JSONObject()
            val rawCode = properties.opt("adcode")?.toString().orEmpty()
            val rawName = properties.optString("name").trim()
            val geometry = feature.optJSONObject("geometry") ?: continue
            val polygons = parseGeometry(geometry)
            if (polygons.isEmpty()) continue
            if (rawCode == "100000_JD") {
                if (includeAuxiliary) auxiliaryPolygons += polygons
                continue
            }
            if (rawCode.isBlank() || rawName.isBlank()) continue

            val parentCode = properties.optJSONObject("parent")?.opt("adcode")?.toString()
            val aliases = aliasesFor(rawName)
            regions += AdministrativeRegion(
                code = rawCode,
                name = rawName,
                parentCode = parentCode,
                parentName = if (parentCode == "100000") "中国" else "暂无数据",
                level = forcedLevel,
                initials = initialsOf(rawName),
                aliases = aliases,
                governmentSeat = centerText(properties),
                postalCode = "暂无数据",
                phoneCode = "暂无数据",
                history = "",
                subdivisionNames = emptyList(),
                childrenCount = 0,
                stats = emptyStats(),
                polygons = polygons,
            )
        }
        return ParseResult(regions, auxiliaryPolygons)
    }

    private fun parseGeometry(geometry: JSONObject): List<List<GeoPoint>> {
        val type = geometry.optString("type")
        val coordinates = geometry.optJSONArray("coordinates") ?: return emptyList()
        return when (type) {
            "Polygon" -> parsePolygon(coordinates)
            "MultiPolygon" -> parseMultiPolygon(coordinates)
            else -> emptyList()
        }
    }

    private fun parseMultiPolygon(coordinates: JSONArray): List<List<GeoPoint>> {
        val polygons = mutableListOf<List<GeoPoint>>()
        for (index in 0 until coordinates.length()) {
            polygons += parsePolygon(coordinates.getJSONArray(index))
        }
        return polygons
    }

    private fun parsePolygon(coordinates: JSONArray): List<List<GeoPoint>> {
        if (coordinates.length() == 0) return emptyList()
        val outerRing = coordinates.getJSONArray(0)
        val polygon = mutableListOf<GeoPoint>()
        for (index in 0 until outerRing.length()) {
            val pair = outerRing.getJSONArray(index)
            polygon += project(pair.getDouble(0), pair.getDouble(1))
        }
        return if (polygon.size >= 3) listOf(polygon) else emptyList()
    }

    private fun project(longitude: Double, latitude: Double): GeoPoint {
        val clampedLatitude = latitude.coerceIn(-85.0, 85.0)
        val mercatorY = ln(tan(PI / 4.0 + Math.toRadians(clampedLatitude) / 2.0)) * 180.0 / PI
        return GeoPoint(longitude.toFloat(), (-mercatorY).toFloat())
    }

    private fun computeBounds(polygons: List<List<GeoPoint>>): GeoRect {
        val points = polygons.flatten()
        val left = points.minOf { it.x }
        val right = points.maxOf { it.x }
        val top = points.minOf { it.y }
        val bottom = points.maxOf { it.y }
        val paddingX = (right - left) * 0.04f
        val paddingY = (bottom - top) * 0.04f
        return GeoRect(left - paddingX, top - paddingY, right + paddingX, bottom + paddingY)
    }

    private fun centerText(properties: JSONObject): String {
        val center = properties.optJSONArray("center")
        return if (center != null && center.length() >= 2) {
            "${center.optDouble(0)}, ${center.optDouble(1)}"
        } else {
            "暂无数据"
        }
    }

    private fun emptyStats(): RegionStats {
        return RegionStats(
            areaKm2 = "暂无数据",
            population = "暂无数据",
            density = "暂无数据",
            gdp = "暂无数据",
            gdpPerCapita = "暂无数据",
            populationTrend = emptyList(),
            areaRank = "暂无数据",
            populationRank = "暂无数据",
        )
    }

    private fun statsFor(
        level: AdminLevel,
        fact: RegionFact?,
        areaRank: Int?,
        populationRank: Int?,
    ): RegionStats {
        if (fact == null) return emptyStats()
        return RegionStats(
            areaKm2 = fact.areaKm2?.let(::formatArea) ?: "暂无数据",
            population = fact.population2020?.let { "2020年常住人口 ${formatPeopleToWan(it)}" } ?: "暂无数据",
            density = fact.density?.let { "2020年约 ${formatNumber(it, 0)} 人/平方公里" } ?: "暂无数据",
            gdp = "暂无统一权威数据",
            gdpPerCapita = "暂无统一权威数据",
            populationTrend = listOfNotNull(
                fact.population2000?.toFloat()?.div(10_000f),
                fact.population2010?.toFloat()?.div(10_000f),
                fact.population2020?.toFloat()?.div(10_000f),
            ),
            areaRank = areaRank?.let { "${level.title}第 ${it} 位" } ?: "暂无数据",
            populationRank = populationRank?.let { "${level.title}第 ${it} 位" } ?: "暂无数据",
        )
    }

    private fun introductionFor(
        region: AdministrativeRegion,
        parentName: String,
        children: List<AdministrativeRegion>,
        fact: RegionFact?,
    ): String {
        val levelText = when (region.level) {
            AdminLevel.Province -> "省级行政区划"
            AdminLevel.City -> "市级行政区划"
            AdminLevel.County -> "区县级行政区划"
        }
        val childLevelText = when (region.level) {
            AdminLevel.Province -> "市级行政区划"
            AdminLevel.City -> "区县级行政区划"
            AdminLevel.County -> "下级行政区划"
        }
        val parentText = parentName.ifBlank { "暂无数据" }
        val centerText = if (region.governmentSeat == "暂无数据") {
            "当前数据未提供地图中心坐标。"
        } else {
            "地图中心坐标为 ${region.governmentSeat}。"
        }
        val childrenText = when {
            children.isNotEmpty() -> {
                val childNames = children.take(12).joinToString("、") { it.name }
                val suffix = if (children.size > 12) "等" else ""
                "当前离线数据记录其下辖 ${children.size} 个${childLevelText}：$childNames$suffix。"
            }
            region.level == AdminLevel.County -> "该层级在当前数据中未继续展开下级行政区划。"
            else -> "当前离线数据尚未完成下级行政区划加载时会显示暂无下辖数据。"
        }
        val statsText = if (fact == null) {
            "人口、面积等统计指标暂未匹配到离线事实表。"
        } else {
            val areaText = fact.areaKm2?.let(::formatArea)?.let { "面积约 $it" }
            val populationText = fact.population2020?.let { "2020年常住人口 ${formatPeopleToWan(it)}" }
            val densityText = fact.density?.let { "人口密度约 ${formatNumber(it, 0)} 人/平方公里" }
            listOfNotNull(areaText, populationText, densityText).joinToString("，").plus("。")
        }
        return "${region.name}在本应用中作为${levelText}展示，上级行政区划为${parentText}，行政区划代码为${region.code}。$centerText$childrenText $statsText 边界轮廓来自内置 DataV.GeoAtlas GeoJSON 数据；人口、面积和密度来自 CityPopulation.de 汇编的普查与地理空间数据。GDP 暂未接入统一权威数据源，因此显示为暂无统一权威数据。"
    }

    private fun loadRegionFacts(context: Context): Map<String, RegionFact> =
        runCatching {
            val json = JSONObject(readAsset(context, REGION_FACTS_ASSET))
            val facts = json.getJSONObject("facts")
            buildMap {
                val keys = facts.keys()
                while (keys.hasNext()) {
                    val code = keys.next()
                    val item = facts.getJSONObject(code)
                    put(
                        code,
                        RegionFact(
                            areaKm2 = item.optDoubleOrNull("areaKm2"),
                            density = item.optDoubleOrNull("density"),
                            population2000 = item.optLongOrNull("population2000"),
                            population2010 = item.optLongOrNull("population2010"),
                            population2020 = item.optLongOrNull("population2020"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())

    private fun rankByLevel(
        regions: List<AdministrativeRegion>,
        facts: Map<String, RegionFact>,
        value: (RegionFact) -> Double?,
    ): Map<String, Int> =
        regions
            .groupBy { it.level }
            .flatMap { (_, levelRegions) ->
                levelRegions
                    .mapNotNull { region ->
                        val fact = facts[region.code] ?: return@mapNotNull null
                        val metric = value(fact) ?: return@mapNotNull null
                        region.code to metric
                    }
                    .sortedByDescending { it.second }
                    .mapIndexed { index, pair -> pair.first to index + 1 }
            }
            .toMap()

    private fun formatArea(areaKm2: Double): String =
        if (areaKm2 >= 10_000) {
            "${formatNumber(areaKm2 / 10_000.0, 2)}万平方公里"
        } else {
            "${formatNumber(areaKm2, if (areaKm2 < 100) 2 else 0)}平方公里"
        }

    private fun formatPeopleToWan(people: Long): String =
        "${formatNumber(people / 10_000.0, 2)}万人"

    private fun formatNumber(value: Double, decimals: Int): String =
        String.format(Locale.US, "%,.${decimals}f", value)

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (!has(name) || isNull(name)) null else optDouble(name).takeIf { it.isFinite() }

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (!has(name) || isNull(name)) null else optLong(name)

    private fun aliasesFor(name: String): List<String> {
        val shortName = name
            .removeSuffix("省")
            .removeSuffix("市")
            .removeSuffix("县")
            .removeSuffix("区")
            .removeSuffix("自治州")
            .removeSuffix("地区")
            .removeSuffix("盟")
        val pinyin = pinyinOf(name)
        return listOf(name, shortName, pinyin, pinyin.replace(" ", "")).distinct().filter { it.isNotBlank() }
    }

    private fun initialsOf(text: String): String =
        pinyinOf(text)
            .split(Regex("\\s+"))
            .mapNotNull { it.firstOrNull()?.toString() }
            .joinToString("")

    private fun pinyinOf(text: String): String =
        pinyinTransliterator.transliterate(text)
            .lowercase()
            .replace(Regex("[^a-z\\s]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun matchScore(region: AdministrativeRegion, query: String): Int {
        val name = region.name.lowercase()
        val aliases = region.aliases.map { it.lowercase() }
        return when {
            name == query -> 0
            aliases.any { it == query } -> 1
            region.initials.lowercase() == query -> 2
            name.contains(query) -> 3
            aliases.any { it.contains(query) } -> 4
            region.initials.lowercase().startsWith(query) -> 5
            else -> Int.MAX_VALUE
        }
    }

    private data class Dataset(
        val regions: List<AdministrativeRegion>,
        val byCode: Map<String, AdministrativeRegion>,
        val byLevel: Map<AdminLevel, List<AdministrativeRegion>>,
        val byLevelAndParent: Map<AdminLevel, Map<String, List<AdministrativeRegion>>>,
        val childrenByParent: Map<String, List<AdministrativeRegion>>,
        val worldBounds: GeoRect,
        val auxiliaryPolygons: List<List<GeoPoint>>,
    )

    private data class ParseResult(
        val regions: List<AdministrativeRegion>,
        val auxiliaryPolygons: List<List<GeoPoint>>,
    )

    private data class RegionFact(
        val areaKm2: Double?,
        val density: Double?,
        val population2000: Long?,
        val population2010: Long?,
        val population2020: Long?,
    )
}
