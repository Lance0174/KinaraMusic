# Kinara 对抗复审报告

复审日期：2026-10-19

本报告覆盖 Android 客户端、Worker、Folia 流光资源、构建脚本、测试、仓库边界和公开发布风险。复审依据源码、现有回归测试、Gradle 构建、Worker 测试和 Folia 浏览器测试；没有把模拟器或真机未执行的行为写成已验证事实。

## 已修复

### 凭据与公开仓库边界

- 清除了源码中的 Solara 默认服务地址和密钥。公开构建默认使用空配置并关闭 Solara。
- 本机构建可通过 `local.properties`、Gradle 属性或环境变量注入 Solara 地址/密钥和逗号分隔的 B 站 Worker 地址，值只进入本地 APK 的 `BuildConfig`，不会进入 Git；公开构建默认不绑定维护者的 Worker。
- README 中的本机 JDK、SDK、签名目录已替换为占位符；`local.properties`、签名文件、APK、ZIP、日志、截图和构建目录均被忽略。
- 用户此前在会话中暴露的 Cloudflare Token 以及历史 Solara 凭据都应立即撤销并轮换。本仓库不保存、不会调用这些值。

### 播放地址安全

`AudioResolver.audioCandidates()` 要求 HTTPS/HTTP、无用户信息/片段、协议对应的默认端口，并执行官方音频域名后缀和当前 APK 配置的 B站 Worker 主机白名单。播放器与 Coil 在每次连接前验证重定向目标，禁止 HTTPS 降级，不向媒体请求转发 Cookie/Authorization。系统 DownloadManager 的后续请求由系统管理，应用不能保证它内部每一跳都遵守同一策略。

### 歌词与回退音源同步

队列 `mediaId` 在跨平台回退时保持稳定，因此仅校验 `mediaId` 会误把旧音源采样用于新音源。进度采样现在同时携带 `平台:平台歌曲 ID` 的录音身份；原生歌词和 Folia 流光都拒绝身份不匹配的采样。逐曲校准仍按同一身份保存，不对某一首歌硬编码偏移。

### 网络资源边界

`OnlineTransport` 对响应体增加 2 MiB 上限，既检查声明长度也限制实际读取量，超限在 JSON/LRC 解析前失败。Android 明文网络配置从整域放行收紧为实际需要的接口和音频域名。

### 搜索状态

搜索归约在只有失败结果、尚未收到其它音源结果时继续保持加载态，直到所有并行请求归约完成；最终没有曲目且存在失败时显示错误，不再提前显示数据态。

## 功能复核结论

- 搜索和回退顺序为网易云、QQ、酷狗、酷我、哔哩哔哩、其它；结果按策略排序，不受响应到达顺序影响。
- QQ 空播放地址、B 站 CDN 失败和酷狗无直连取流都会按失败继续向后尝试，不把不可用结果伪装成可播放。
- B 站 Worker 只接受 BVID，音频目标和每次重定向都执行可信 CDN 校验；匿名上游仍可能返回 `-412` 风控，这是外部服务限制，不在客户端伪造成功。
- Folia 流光是设置中的歌词渲染模式，不是额外播放页；WebView 只读离线资源，失败时回退逐字辉光。封面与歌词支持左右滑动和轻点切换。
- 在线元信息同步按实际平台 ID 更新专辑图片和系统媒体信息；导入歌单缺图会在联网后补全并持久化。
- 《与花逝去的我》的设备端延迟没有在当前环境复现。网易云当前返回的版本没有可靠 YRC 词时间，因此保留逐曲校准入口，不猜测固定秒数。

## 验证

以下是首轮基线结果，不能用于证明后续改动通过。第二轮结果与证据另见下节。

- Android `:app:testDebugUnitTest`：207 项通过，0 失败，0 错误。
- Android `:app:lintDebug`：通过；仅保留现有 Kotlin/Compose 警告，无 lint 错误。
- Android `:app:assembleRelease`：通过；发布签名来自仓库外配置。
- Worker `npm test`：9 项通过。
- Folia `npm test`：1 项通过。
- Folia `npm run test:browser`：3 项通过，覆盖 WebGL、时钟先到、暂停、换歌、空歌词和上下文丢失。
- 未执行真机音频输出、厂商后台策略、蓝牙和 B 站账号登录后的端到端播放验收；这些保留为设备验收项。

## 第二轮：全仓库与恶意植入排查

检查范围、上游比对、依赖审计结果及未覆盖项见 [供应链复审](SUPPLY_CHAIN_REVIEW.md)。在已检查范围内未发现可确认的恶意植入，但发现并修复了重定向校验缺口、文本请求取消竞态、外部媒体控制器权限过宽、下载换源身份丢失、B站备用 CDN 中断、设置凭据进入备份、非持久授权导入无大小上限等问题。

Worker 开发依赖从 7 项已知漏洞更新到 0；Folia 81 个原文件哈希与上游固定提交全部吻合，使用官方 npm 包重新构建的脚本与原资源逐字节一致。Gradle wrapper 原来属于官方旧版本，本轮换成与分发版本一致的官方 8.9 wrapper 并增加分发校验；未把版本差异误报为后门。

本轮最终验证（1.3.1 / versionCode 9）：

- 关闭 Gradle 构建缓存与 Kotlin 增量编译后执行 `:app:clean :app:testDebugUnitTest :app:lintDebug :app:assembleRelease -PciUnsigned=true`，使用严格依赖摘要校验：通过，89 个任务中 87 个实际执行。
- Android 226 项测试通过，0 失败、0 错误、0 跳过；包括实际 HTTP 重定向链、多跳取消、外部控制器权限、换源下载身份及导入上限测试。
- Debug Lint、Release Lint Vital、R8 压缩与资源收缩、未签名 Release 打包通过。保留既有未使用参数和测试文件可空接收器编译警告，不冒充零警告。
- 公共 Release 的 `SOLARA_BASE_URL`、`SOLARA_SECRET`、`BILI_WORKER_BASES` 验证为空；本机构建仍可按外部配置注入，测试没有公开本机私密配置。
- Worker 13/13；Folia Node 1/1；Folia 浏览器/WebGL 3/3；Wrangler dry-run 打包全部通过。未部署线上 Worker，也未发布 GitHub Release。
- Folia 资源经官方依赖重建保持一致；新增换行规则使 Windows 和 CI 的源码摘要一致。
- 805 项 Gradle 摘要全部匹配官方文件；449 个 Maven 包版本的公告查询命中 16 个构建/测试包版本，均不在 `releaseRuntimeClasspath` 中。Kotlin 缓存风险已限制复用，完整工具链升级尚未完成；详见依赖公告记录和后续 Issue。

Windows 本机 JDK 访问 Maven 出现 TLS 失败，依赖通过仅访问官方 HTTPS 仓库的临时本地缓存核验并下载。最终干净构建以离线方式使用这些已核验依赖，公开 CI 直接使用官方仓库。

## 发布前检查

1. 在 GitHub 仓库设置中确认 Actions 只使用仓库 Secrets，不把 Token 写入 workflow 或 issue。
2. 轮换历史 Cloudflare Token 和任何曾用于 Solara 的密钥。
3. 由维护者在自己的 `local.properties` 中配置需要的音源和签名，先运行全部验证命令再发布 APK。
4. 在至少一台 Android 12 设备上验收液态玻璃模糊、歌词时钟、后台播放、通知栏、B 站登录和音频回退。
