# GeoChina

离线看清中国行政层级。

中国行政区划地图 Android App 原型。

## 已实现

- Jetpack Compose + Material 3 单 Activity 应用
- 高德地图 Android SDK 在线真实地图底图，叠加本地行政区划专题层
- Canvas 纯矢量行政区划专题层，可叠加在在线底图之上
- 离线行政区划数据：真实中国陆地轮廓、省/市/区县边界和南海辅助边界
- 双指缩放、拖动、双击放大、双指点击缩小、右下角缩放按钮
- 按缩放级别自动切换省级 / 市级 / 区县级粒度，并带淡入淡出过渡
- 市级和区县级按当前视口焦点分层显示：焦点省/市下级区域正常显示，外围区域弱化为背景上下文并保留上一级名称
- 县级视图支持高倍率矢量放大，便于查看区县边界细节
- 首屏仅同步加载省级边界，市级和区县级数据后台分批预加载，减少启动白屏时间
- 区域点击 BottomSheet，包含基本信息、详细信息、统计数据三个 Tab
- 搜索栏实时匹配名称、别名和拼音首字母，支持搜索历史
- 收藏列表，收藏数据通过 Room 写入本地 SQLite
- 跟随系统 / 浅色 / 深色主题切换

## 数据说明

当前仓库已内置离线 GeoJSON 边界数据：

- `app/src/main/assets/mapdata/province.geojson`：省级边界
- `app/src/main/assets/mapdata/city.geojson`：市级边界
- `app/src/main/assets/mapdata/county.geojson`：区县级边界

数据来自 DataV.GeoAtlas `areas_v3` GeoJSON 接口，App 启动时由 `ChinaAdminDataset` 解析为真实行政区 polygon 后在 Canvas 上绘制。省级文件中的 `100000_JD` 几何会作为九段线辅助边界绘制，不参与搜索和点击。

在线底图通过高德地图 Android SDK 加载。工程使用 Maven Central 中可直接解析的 `com.amap.api:3dmap:10.0.600`，在 Compose 中通过 `TextureMapView` 承载高德矢量底图，并关闭 SDK 手势，由 App 自己统一处理缩放、拖动和专题层点击。

高德地图 SDK 需要有效的 Android Key。请在本地 `local.properties` 中配置，或通过 Gradle 属性 / 环境变量传入：

```properties
AMAP_API_KEY=你的高德AndroidKey
```

Key 会通过 Manifest placeholder 写入 `com.amap.api.v2.apikey`。创建 Key 时需绑定本 App 的包名 `com.geochina.app` 以及签名 SHA1。App 启动时会按高德 SDK 合规要求调用隐私展示和同意接口，并将 SDK 缓存目录指向应用缓存目录 `cacheDir/amap`。

注意：DataV.GeoAtlas 是面向可视化场景的简化边界数据，适合本离线原型和非商业演示。正式发布、商用或对地图合规性有严格要求时，应替换为经审定的标准地图数据，并复核审图号、南海诸岛、九段线、台湾省及港澳边界等表达。

## 构建

```bash
./gradlew assembleDebug
```

构建产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```
