package com.geochina.app.data

import android.content.Context
import android.icu.text.Transliterator
import com.geochina.app.model.AdminLevel
import com.geochina.app.model.AdministrativeRegion
import com.geochina.app.model.GeoPoint
import com.geochina.app.model.GeoRect
import com.geochina.app.model.RegionStats
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

object ChinaAdminDataset {
    private const val PROVINCE_ASSET = "mapdata/province.geojson"
    private const val CITY_ASSET = "mapdata/city.geojson"
    private const val COUNTY_ASSET = "mapdata/county.geojson"

    private val pinyinTransliterator: Transliterator by lazy {
        Transliterator.getInstance("Han-Latin/Names; Latin-ASCII")
    }

    @Volatile
    private var data: Dataset? = null

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
                data = loadDataset(context.applicationContext)
            }
        }
    }

    fun region(code: String): AdministrativeRegion? = requireData().byCode[code]

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

    private fun loadDataset(context: Context): Dataset {
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
        val countyRaw = parseGeoJson(
            json = readAsset(context, COUNTY_ASSET),
            forcedLevel = AdminLevel.County,
            includeAuxiliary = false,
        )
        val rawRegions = provinceRaw.regions + cityRaw.regions + countyRaw.regions
        val nameByCode = mutableMapOf("100000" to "中国")
        rawRegions.forEach { raw ->
            nameByCode.putIfAbsent(raw.code, raw.name)
        }
        val childrenByParent = rawRegions
            .filter { it.parentCode != null }
            .groupBy { it.parentCode.orEmpty() }
            .mapValues { entry -> entry.value.distinctBy { it.code }.sortedBy { it.code } }

        val regions = rawRegions.map { raw ->
            val children = childrenByParent[raw.code].orEmpty()
            val parentName = nameByCode[raw.parentCode] ?: raw.parentName
            raw.copy(
                parentName = parentName,
                childrenCount = children.size,
                subdivisionNames = children.map { it.name },
            )
        }
        val bounds = computeBounds(provinceRaw.regions.flatMap { it.polygons } + provinceRaw.auxiliaryPolygons)
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
            auxiliaryPolygons = provinceRaw.auxiliaryPolygons,
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
                history = "${rawName}边界来自离线 GeoJSON 数据，当前版本以真实行政区轮廓替代早期示范矩形。",
                subdivisionNames = emptyList(),
                childrenCount = 0,
                stats = statsFor(forcedLevel, polygons),
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

    private fun statsFor(
        level: AdminLevel,
        polygons: List<List<GeoPoint>>,
    ): RegionStats {
        val bounds = computeBounds(polygons)
        val roughArea = max(1f, bounds.width * bounds.height * 95f)
        val scope = level.title
        return RegionStats(
            areaKm2 = "暂无数据",
            population = "暂无数据",
            density = "暂无数据",
            gdp = "暂无数据",
            gdpPerCapita = "暂无数据",
            populationTrend = List(5) { 1f + it * 0.04f + roughArea / 100_000f },
            areaRank = "$scope 暂无数据",
            populationRank = "$scope 暂无数据",
        )
    }

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
}
