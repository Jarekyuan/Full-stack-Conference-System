# Day 5：会议纪要 + 部署/CI + 面试彩排

> 时间：6 小时
> 目标：把“音频上传 → COS 分片 → ASR 转写 → 语音文本联动 → DeepSeek 纪要 → Word 导出”讲完整，
> 再把 Docker/Nginx/CI 的请求路径补齐，最后做一次 15 分钟演示。
> 今天的手敲是**ASR 解析测试 + `splitSentences` 边界测试 + `verify-deploy`**。

---

## 1. 今日目标

- [ ] 能画出会议纪要全链路，讲清 COS 分片和 ASR 状态机
- [ ] 能讲清虚拟滚动和语音文本联动的实现
- [ ] 能讲清 compose/Nginx/CI 的请求路径和故障点
- [ ] 完成一次 15 分钟项目演示

---

## 2. 面试先问

1. 大文件为什么直传 COS？分片和断点续传怎么实现？
2. ASR 任务重复/乱序怎么防？`asr_started_at` 有什么用？
3. 为什么用 `ResTextFormat=0`，不用句级/词级？
4. 语音文本联动怎么做到逐句高亮？虚拟滚动为什么能减少 DOM？
5. 容器重建后 Nginx 怎么避免 502？CI 的四个 job 分别做什么？

---

## 3. 10 分钟概念

### 3.1 会议纪要全链路

```text
浏览器选音频
  → POST /minutes/upload/init（5MiB 分片 + 预签名 URL）
  → 浏览器 3 并发直传 COS（文件字节不过后端）
  → POST /minutes/upload/complete
  → POST /meetings/{id}/minutes/transcribe
  → 腾讯 ASR CreateRecTask（SourceType=URL，16k_zh，话者分离）
  → 定时任务 DescribeTaskStatus 轮询
  → 解析 Result（[start,end,speaker] text）→ 落 speaker/segment
  → 工作台：音频播放 + 转写逐句高亮
  → DeepSeek 异步生成结构化纪要
  → 导出 Word
```

### 3.2 ASR 状态机

```text
0 未转写
  → 10 待识别（startAsr 重置旧转写/AI，写 asr_started_at run token）
  → 20 识别中（CreateRecTask 成功，记 asr_task_id）
  → 30 成功（解析+落库）
  → 40 失败（重试耗尽）
```

- `asr_started_at` 是 run token：重新上传/重试会写新 token；旧 worker 回来 CAS 不命中，结果丢弃
- 定时任务扫 10/20/卡死 20；调度器 4 线程，避免 AI/ASR/清理互相饿死

### 3.3 分片上传

```text
init：objectKey + uploadId + totalParts + chunkSize
partUrls：给缺失分片签发预签名 PUT URL
浏览器：3 并发 PUT，记录 ETag
complete：先 listParts 做大小预检 → completeMultipart → file_object
```

- 5MiB 分片；预签名 URL 60 分钟
- 前端 localStorage 记录 uploadId + completed，刷新后按缺失分片续传
- complete 在 DB 事务外执行：远端调用不占数据库连接
- 已完成会话是幂等终点：重复 complete 直接返回

### 3.4 语音文本联动

```text
ASR 段：[startMs, endMs] + text
  → 按标点切子句
  → 子句时间按字符数比例估算
  → 播放时二分找当前段/当前子句
  → 高亮 + 自动滚动
  → 点击子句 → audio.currentTime = startMs / 1000
```

### 3.5 虚拟滚动

- `@tanstack/react-virtual` 只渲染视口附近的行（overscan=8）
- 行高由文本换行决定：`measureElement` 动态测量，不假设固定行高
- 播放高亮时 `scrollToIndex` 自动跟随
- 300 段实测只渲染十几行

### 3.6 部署拓扑

```text
浏览器 :8000
  → Nginx
     ├── /api/**        → gateway:8080 → auth/meeting/notification
     ├── /ws/**         → gateway:8080
     └── /files/local/** → meeting-service:8082
  → 4 个 Java 服务 + web 在 smt-net
  → MySQL/Redis/RabbitMQ/Nacos 只在容器网络与回环
```

- compose：9 个常驻容器 + `mysql-init` 一次性任务
- Nginx：`resolver 127.0.0.11` 动态解析；日志不记 query
- CI：backend / frontend / compose-config / docker-build；compose smoke 手动

---

## 4. 代码走读

### 4.1 后端：分片上传

文件：
- `server/meeting-service/src/main/java/com/smt/meeting/service/MultipartUploadService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/CosObjectStorage.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/LocalObjectStorage.java`

`MultipartUploadService`：

- `init`：校验会议/权限，生成 objectKey，调 `storage.initMultipart`
- `partUrls`：校验分片号，`storage.presignPart`
- `session`：已完成直接返回终态；进行中 `listParts` 回填已上传/缺失分片
- `complete`：
  - 先 `listParts` 做大小预检（complete 会消费 uploadId，事后发现大小不符无法重试）
  - `completeMultipart` 在 DB 事务外执行
  - 事务内写 `file_object`、更新会话状态
  - 已完成会话重复 complete 幂等返回

`CosObjectStorage`：

- COS SDK `initiateMultipartUpload` / `listParts` / `completeMultipartUpload` / `generatePresignedUrl`
- 分片 ETag 按 partNumber 排序提交
- 曾踩坑：COS SDK 对不可变 `Stream.toList()` 原地排序 → `UnsupportedOperationException`；改传 `ArrayList`

`LocalObjectStorage`：

- 本地目录 + HMAC 预签名，用于 CI/无 COS 环境
- 支持 Range 下载

### 4.2 后端：ASR

文件：
- `server/meeting-service/src/main/java/com/smt/meeting/service/MinutesAsrService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/TencentAsrClient.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/AsrResultParser.java`
- `server/meeting-service/src/main/java/com/smt/meeting/task/MinutesAsrTask.java`

`MinutesAsrService`：

- `startAsr`：Redisson 会议锁，校验音频文件；重置旧 speaker/segment；`startAsr` 写 `asr_status=10` + `asr_started_at` run token
- `process`：10 → `submit`；20 → `poll`
- `submit`：`storage.presignGet` 生成 6 小时 URL → `asrClient.submit(url)` → `markAsrSubmitted` 带 run token CAS
- `poll`：WAITING/PROCESSING 保持 20；FAILED/空结果走失败重试；SUCCESS 落库
- `persistSuccess`：锁定 minutes 行，校验 `asr_status=20 && asr_started_at=startedAt`，`markAsrSuccess` CAS；删旧 speaker/segment，插入新数据
- 失败：`markAsrRetry` / `markAsrFailed`；查询抖动保持 20，卡死扫描兜底

`TencentAsrClient`：

- `CreateRecTaskRequest`：`EngineModelType=16k_zh`、`ChannelNum=1`、`ResTextFormat=0`、`SourceType=0`（URL）、`SpeakerDiarization=1`
- `DescribeTaskStatus`：状态 0/1/2/3 → WAITING/PROCESSING/SUCCESS/FAILED
- 优先解析 `TaskStatus.Result`，回退 `ResultDetail`

`AsrResultParser`：

- 正则 `[开始,结束,说话人] 文本`
- 支持 `m:ss.SSS` / `h:mm:ss.SSS`
- 时间越界/倒序/空文本跳过；供应商字段按不可信输入处理

`MinutesAsrTask`：

- 每 5 秒扫待提交（10）、识别中（20）、卡死（20 超过 120 分钟）
- 任务锁只包住“选 id”，条目锁包住单条处理

### 4.3 后端：AI 纪要

文件：
- `server/meeting-service/src/main/java/com/smt/meeting/service/DeepSeekLlmClient.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/MinutesAiService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/task/MinutesAiTask.java`

- `MinutesAiService.request`：校验转写存在、冷却时间、状态，写 `ai_status=10`
- `MinutesAiTask` 异步扫 10/20/卡死
- `process`：拼 prompt（转写 + 会议信息）→ `LlmClient.completeJson` → `MinutesSummaryParser` 校验字段 → 落 `summary_json`
- DeepSeek 用 JSON mode；失败重试/卡死恢复；mock provider 返回与转写一致的抽取式摘要
- 前端 `aiProvider` 显示“本地演示”标签，避免把 mock 当真实模型

### 4.4 前端：上传与工作台

文件：
- `web/src/components/minutes/MinutesUploader.tsx`
- `web/src/components/minutes/AudioTranscriptWorkspace.tsx`
- `web/src/components/minutes/TranscriptList.tsx`
- `web/src/utils/transcriptSync.ts`
- `web/src/components/minutes/MinutesSummary.tsx`
- `web/src/components/minutes/MinutesInsights.tsx`
- `web/src/hooks/useMultipartUpload.ts`

`MinutesUploader`：

- 只接受音频；上传前 `browserCanPlay(file)` 读取 `<audio>` 元数据，ALAC 直接拦截
- `useMultipartUpload` 上传；成功后 `startTranscription(meetingId, fileId)`
- 支持暂停/继续；上传会话过期时重新 init

`AudioTranscriptWorkspace`：

- `asrStatus 10/20`：显示“正在识别”，轮询详情
- `asrStatus 40`：失败提示 + 重新上传
- `asrStatus 30` 或历史 0：拉 transcript
- 音频 URL 过期自动重签；`audioRef` + `findActiveSentence` 做高亮
- 请求 token（`transcriptRequestRef` / `audioRequestRef`）防止旧响应覆盖新状态

`TranscriptList`：

- `useVirtualizer` + `measureElement`
- 每段按标点切子句，当前子句一个 `<span>` 高亮
- 点击子句 → `onSeek(startMs)`
- 播放时 `scrollToIndex(activeIndex)`

`transcriptSync`：

- `splitSentences`：中文/英文标点切分，标点保留在前一句
- `splitSegmentIntoSentences`：按字符数比例估算子句时间
- `findActiveSentence` / `findActiveSegment`：二分查找当前段/子句

`useMultipartUpload`：

- 3 并发；每片失败 1s/2s/4s 退避；`AbortController` 暂停在途请求
- localStorage 记录 uploadId + completed；刷新后 listParts 续传
- runId 隔离：暂停/重置后旧 run 的响应全部丢弃

### 4.5 部署与 CI

文件：
- `server/*/Dockerfile`、`web/Dockerfile`
- `deploy/docker-compose.yml`
- `deploy/nginx.conf`
- `.github/workflows/ci.yml`
- `scripts/verify-deploy.sh`

关键点：

- Dockerfile：Maven 多阶段 + BuildKit 缓存 + 非 root；web 用 node 构建 → nginx 托管
- compose：infra 4 + 服务 4 + web 1；`mysql-init` 一次性建最小权限账号；健康检查
- Nginx：SPA fallback、`/api`、`/ws` Upgrade、`/files/local`；`resolver 127.0.0.11`；日志只记 `$uri`
- CI：backend service containers、frontend、compose-config、docker-build（`nginx -t` + jar 密钥扫描）
- `verify-deploy.sh`：首页/深层路由/登录/会议室/会议/Nacos/`/files/local`/WS，共 15 项

### 4.6 📘 笔记引用

> 📘 **笔记引用**：《2.React知识点》§四.6 自定义 Hooks / §四.5 useEffect 异步坑点
> 类型：A（项目直接对应）
> 笔记结论：自定义 Hook 复用状态逻辑；异步请求要处理竞态/卸载。
> 项目落点：`useMultipartUpload`、`useWebSocket`；`AudioTranscriptWorkspace` 的请求 token。
> 差异/提醒：项目用 ahooks `useRequest` 管普通请求，分片上传是手写状态机。

> 📘 **笔记引用**：《2.React知识点》§六 性能优化 / §七 原理深入
> 类型：A/B（项目直接对应 + 有差异）
> 笔记结论：列表渲染要关注 key、memo、虚拟化；React 渲染是 Fiber 调度。
> 项目落点：`TranscriptList` 用 `@tanstack/react-virtual`，不是手写虚拟列表。
> 差异/提醒：项目没有深入 Fiber 调度，面试讲“虚拟滚动减少 DOM + 动态测量”即可。

> 📘 **笔记引用**：《1.前端基础知识点》§三 Web API
> 类型：A/C（项目直接对应 + 基础补充）
> 笔记结论：File/Blob/ArrayBuffer/Audio 是浏览器文件与媒体能力的基础。
> 项目落点：`MinutesUploader` 的 `File`、`browserCanPlay` 的 `<audio>` 元数据、下载的 `Blob`。
> 差异/提醒：这部分是前端基础，和项目场景自然对应。

> 📘 **笔记引用**：《4.前端工程化》§4 测试与 CI / §5 构建工具与性能 / §9 浏览器安全
> 类型：A/B/C（项目直接对应 + 有差异）
> 笔记结论：质量门禁要统一命令；构建要看 code splitting；CORS/XSS 是安全边界。
> 项目落点：`vite.config.ts` 的 `manualChunks`、`.github/workflows/ci.yml`、Nginx `Host $http_host`。
> 差异/提醒：笔记用 pnpm/Next.js，项目用 npm/Vite；讲概念和取舍，不硬套工具。

> 📘 **笔记引用**：《13.微服务+部署》§四 Docker / §五 Nginx / §六 上线
> 类型：A（项目直接对应）
> 笔记结论：Dockerfile 多阶段构建、compose 一键起、Nginx 反代/负载均衡。
> 项目落点：4 个 Dockerfile、`deploy/docker-compose.yml`、`deploy/nginx.conf`。
> 差异/提醒：项目是单机 compose，不是 K8s；上线清单里的 HTTPS/域名按“了解”即可。

> 📘 **笔记引用**：《10.Java基础》§十 IO 流与文件读写 / §六 序列化
> 类型：A/C（项目直接对应 + 基础补充）
> 笔记结论：流式读写避免大文件进内存；JSON 序列化要统一格式。
> 项目落点：分片上传的流式写、`ObjectMapper` 的 LocalDateTime 格式。
> 差异/提醒：项目没有手写 IO，使用 COS SDK/Spring 的流；理解背压和内存边界即可。

---

## 5. 手敲练习

### 5.1 ASR 解析测试

在 `server/meeting-service/src/test/java/com/smt/meeting/service/AsrResultParserTest.java` 增加用例（学习分支）：

```java
@Test
void parsesValidLinesAndSkipsInvalid() {
    String result = """
            [0:1.030,0:8.570,0]  恩格斯论述了雅典国家。
            [bad line]
            [0:9.000,0:8.000,1] 结束早于开始
            [0:10.000,0:12.000,1] 这是第二段。
            """;
    var sentences = AsrResultParser.parse(result);
    assertEquals(2, sentences.size());
    assertEquals("恩格斯论述了雅典国家。", sentences.get(0).text());
    assertEquals(1030L, sentences.get(0).startMs());
    assertEquals("这是第二段。", sentences.get(1).text());
}
```

Run:

```bash
cd server
mvn -pl meeting-service -Dtest=AsrResultParserTest test
```

验收：正常行解析、非法行跳过、时间单位正确。

### 5.2 `splitSentences` 边界测试

在 `web/tests/utils/transcriptSync.test.ts` 增加：

```ts
it('splits by Chinese/English punctuation and keeps punctuation', () => {
  expect(splitSentences('你好，世界。Hello!')).toEqual(['你好，', '世界。', 'Hello!'])
})

it('returns empty for blank text', () => {
  expect(splitSentences('   ')).toEqual([])
})

it('allocates time by character weight', () => {
  const result = splitSegmentIntoSentences({ id: 1, startMs: 0, endMs: 1000, text: '你好，世界。' })
  expect(result).toHaveLength(2)
  expect(result[1].endMs).toBe(1000)
})
```

Run:

```bash
cd web
npm test -- tests/utils/transcriptSync.test.ts
```

验收：边界用例通过；能解释“时间是按字符数比例估算，不是 ASR 真词级时间戳”。

### 5.3 部署验收

```bash
bash scripts/verify-deploy.sh
```

Expected: `通过 15，失败 0`（COS 模式加 `CHECK_LOCAL_FILES=0`）。

### 5.4 15 分钟演示

按 `docs/08-面试演示与高频追问.md` 的时间轴走一遍：

- 0:00–1:30 项目定位
- 1:30–3:30 架构
- 3:30–6:30 并发预约
- 6:30–8:30 审批/状态机
- 8:30–11:30 会议纪要
- 11:30–13:30 部署/CI
- 13:30–15:00 取舍/踩坑

验收：不看稿能讲完；每个模块能落到 1-2 个文件和 1 个取舍。

---

## 6. 五道自测

### 题目

1. 预签名 URL 为什么不让文件经过后端？
2. ASR run token 解决什么问题？
3. `ResTextFormat=0` 和 `3` 的区别？
4. 虚拟滚动为什么能减少 DOM？动态行高怎么处理？
5. 容器重建后 Nginx 怎么避免 502？

### 答案

1. 文件字节不经过后端，避免大文件占用 Tomcat 线程/内存；后端只签名和管元数据；分片 + 断点续传解决大文件和弱网。
2. 重新上传/重试会写新的 `asr_started_at`；旧 worker 回来 CAS 不命中，结果丢弃，不会覆盖新一轮转写。
3. `0` 返回带时间戳的段落文本（`[start,end,speaker] text`），更接近旧版体验；`3` 走句级切分，粒度太细（曾产生 958 个碎段）。
4. 只渲染视口附近的行，DOM 数量从 N 降到十几；动态行高用 `measureElement` 实测，不假设固定高度。
5. Nginx 用 `resolver 127.0.0.11` + 变量 `proxy_pass`，在 valid 周期内重新解析服务名，不缓存旧 IP；容器重建后无需重启 web。

---

## 7. 复盘卡

```text
今天最重要的 3 句话：
1. 大文件直传 COS：后端签名，前端分片，complete 前做大小预检。
2. ASR 用 run token + 状态机，旧任务回来不会覆盖新一轮。
3. 语音文本联动 = 段级时间戳 + 标点切子句 + 比例估算 + 虚拟滚动。

可以讲给面试官的 1 个点：
“ASR 958 个碎段”的修复：ResTextFormat 从 3 改回 0，恢复时间戳段落体验。
```
