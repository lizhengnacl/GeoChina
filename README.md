# GeoChina

中国行政区划离线地图 Android App 原型。

## 已实现

- Jetpack Compose + Material 3 单 Activity 应用
- Canvas 纯矢量地图渲染，无在线底图和网络权限
- 双指缩放、拖动、双击放大、双指点击缩小、右下角缩放按钮
- 按缩放级别自动切换省级 / 市级 / 区县级粒度，并带淡入淡出过渡
- 市级和区县级按当前视口焦点裁剪显示：聚焦省份时只展示该省地市，聚焦城市时只展示该市区县
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

注意：DataV.GeoAtlas 是面向可视化场景的简化边界数据，适合本离线原型和非商业演示。正式发布、商用或对地图合规性有严格要求时，应替换为经审定的标准地图数据，并复核审图号、南海诸岛、九段线、台湾省及港澳边界等表达。

## 构建

```bash
./gradlew assembleDebug
```

构建产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```
