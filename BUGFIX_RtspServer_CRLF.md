# Bug 修复说明：RtspServer 不响应标准 CRLF 请求

日期：2026-09-22
现象：PC 端（FFmpeg / OpenCV / VLC）无法拉流，RTSP 握手超时失败。

---

## 1. 问题现象

- PC 端 OpenCV 拉流 `rtsp://192.168.121.175:8554/live`（TCP 交错），27 秒后 `cap.isOpened() == False`。
- 设备状态正常：MQTT 在线、state 上报正常、App 显示推流中。

## 2. 排查结论（网络层无问题）

| 检查项 | 结果 |
|---|---|
| PC IP | 192.168.121.170（Wi-Fi） |
| 手机 IP | 192.168.121.175（同一网段） |
| ping | 通，平均 76ms |
| TCP 8554 端口 | 可连接 |

**结论：问题不在网络，在手机端 RTSP 信令解析。**

## 3. 实测证据

用原始 socket 向 `192.168.121.175:8554` 发 OPTIONS 请求：

| 请求行结尾 | 结果 |
|---|---|
| 标准 **CRLF**（`\r\n`，RTSP 标准，FFmpeg/VLC 均如此） | **无任何响应**（超时） |
| **LF**（`\n`） | 正常返回 `200 OK`，`Server: AndroidNetworkCamera` |

## 4. 根因

文件：`CameraApp/app/src/main/java/com/von/cameraapp/rtsp/RtspServer.kt`
位置：`RtspReader.readLine()`（约 254~266 行）

当前代码：

```kotlin
if (b == '\n'.code) break
if (prev != '\r'.code) sb.append(b.toChar())   // ← 条件写反了
```

本意是"丢弃 `\r`"，但实际逻辑是"前一个字符是 `\r` 才丢弃当前字符"，
导致 `\r` 本身被追加进行内容：

- 请求行解析为 `"OPTIONS rtsp://... RTSP/1.0\r"`（尾部多了 `\r`）
- 方法名匹配失败，服务器静默丢弃请求 → 客户端超时
- LF 请求不含 `\r`，所以能正常工作（与实测一致）

## 5. 修复方法（一行）

```kotlin
// 修改前
if (prev != '\r'.code) sb.append(b.toChar())

// 修改后
if (b != '\r'.code) sb.append(b.toChar())
```

（`prev` 变量此后不再被使用，可一并删除 `var prev = -1` 和 `prev = b` 两行。）

## 6. 修复后验证

1. 重新编译并安装 APK 到手机，开启推流。
2. PC 端任选其一验证：
   - VLC 打开网络串流 `rtsp://192.168.121.175:8554/live`
   - ffprobe：`ffprobe -rtsp_transport tcp rtsp://192.168.121.175:8554/live`
   - flux_vision_3d 的 NetCamera 调试工具 → 点"拉流预览"
3. 预期：能正常出画面。
