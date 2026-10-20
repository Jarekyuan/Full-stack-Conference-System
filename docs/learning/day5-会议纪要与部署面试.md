# Day 5：会议纪要 + 部署/CI + 面试彩排

> 时间：6 小时
> 目标：把“音频上传 → COS 分片 → ASR 转写 → 语音文本联动 → DeepSeek 纪要 → Word 导出”讲完整，
> 再把 Docker/Nginx/CI 的请求路径补齐，最后做一次 15 分钟演示。
> 今天的手敲是**ASR 解析测试 + `splitSentences` 边界测试 + `verify-deploy` + 15 分钟彩排**。

---

## 1. 今日目标

### 1.1 学完能做到

- [ ] 能画出会议纪要全链路，讲清 COS 分片和断点续传
- [ ] 能讲清 ASR 状态机、run token、结果解析
- [ ] 能讲清语音文本联动的逐句高亮和虚拟滚动
- [ ] 能讲清 DeepSeek 异步 worker 和 mock/真实 provider
- [ ] 能讲清 compose/Nginx/CI 的请求路径和故障点
- [ ] 完成一次 15 分钟项目演示

### 1.2 时间分配（6h）

| 时间段 | 内容 | 产出 |
|--------|------|------|
| 0:00–1:00 | 概念：上传、ASR、联动、AI、部署 | 一张全链路图 |
| 1:00–2:30 | 后端走读：分片、ASR、AI、导出 | 能口述状态机 |
| 2:30–3:30 | 前端走读：上传器、工作台、虚拟列表 | 能口述联动 |
| 3:30–4:30 | 部署/CI 走读 | 能口述请求路径 |
| 4:30–5:30 | 手敲：解析测试、splitSentences、verify-deploy | 测试通过 |
| 5:30–6:00 | 15 分钟演示 + 自测 + 复盘 | 不卡壳 |

### 1.3 前置

- compose 栈可用，且 `COS_*` 已配置（音频只走 COS）
- ASR/AI 可先用 `mock`；演示真实链路时再切 `tencent/deepseek`
- 准备一个可播放的音频（AAC/M4A/MP3；不要 ALAC）

### 1.4 今天的“一张图”

```text
浏览器选音频
  → initMultipart（5MiB 分片 + 预签名 URL）
  → 3 并发直传 COS（文件字节不过后端）
  → complete（listParts 大小预检 → 合并 → file_object）
  → POST /minutes/transcribe
  → 腾讯 ASR CreateRecTask（16k_zh，话者分离，ResTextFormat=0）
  → 定时任务 DescribeTaskStatus 轮询
  → 解析 [start,end,speaker] text → speaker/segment
  → 工作台：音频 + 逐句高亮 + 点击 seek
  → DeepSeek 异步生成结构化纪要
  → Word 导出
```

记住三句话：

- **大文件直传：后端签名，前端分片，complete 前预检**
- **ASR 用 run token + 状态机，旧任务不覆盖新任务**
- **联动 = 段级时间戳 + 标点切子句 + 比例估算 + 虚拟滚动**

---

## 2. 面试先问

先自己想 30 秒，答案在第 6 节。

1. 大文件为什么直传 COS？分片和断点续传怎么实现？
2. complete 之前为什么要 listParts 做大小预检？
3. ASR 任务重复/乱序怎么防？`asr_started_at` 有什么用？
4. 为什么用 `ResTextFormat=0`，不用句级/词级？
5. 语音文本联动怎么做到逐句高亮？虚拟滚动为什么能减少 DOM？
6. DeepSeek 生成是同步还是异步？失败/卡死怎么恢复？
7. 容器重建后 Nginx 怎么避免 502？CI 的四个 job 分别做什么？
8. 音频上传后浏览器不能播放，怎么排查？

> 这 8 个问题覆盖“对象存储 + 外部 API 状态机 + 前端复杂交互 + 部署排障”，是项目最有差异化的部分。

---

## 3. 10 分钟概念

### 3.1 为什么大文件不经过后端

如果浏览器把 150MB 音频 POST 给后端：

- 占用 Tomcat 线程/内存
- 网关/Nginx 要调大 body size
- 后端还要转发到 COS，双倍流量
- 网络抖动导致整包重传

预签名直传：

```text
后端只负责：
  1. 校验权限/文件元数据
  2. 生成 objectKey + uploadId
  3. 给每个分片签发预签名 PUT URL
浏览器：
  直接 PUT 到 COS
后端：
  只收 complete 的元数据（partNumber + ETag）
```

好处：

- 文件字节不过后端
- 分片可并发、可重试、可续传
- 后端只处理轻量元数据

### 3.2 分片上传的状态

```text
init：
  objectKey = minutes/{meetingId}/{yyyyMM}/{uuid}.{ext}
  uploadId = COS 返回
  totalParts = ceil(size / 5MiB)
  file_upload_session 落库（status=0）

partUrls：
  给缺失分片签发预签名 PUT URL（60 分钟）

浏览器：
  3 并发 PUT
  记录每片 ETag
  localStorage 记录 uploadId + completed
  失败 1s/2s/4s 退避
  暂停：AbortController

complete：
  listParts 对比已上传/缺失
  大小预检（总大小 == 预期）
  completeMultipartUpload（按 partNumber 排序的 ETag）
  写 file_object，会话 status=10
```

### 3.3 为什么 complete 前要大小预检

COS 的 `completeMultipartUpload` 会**消费 uploadId**：

- 合并后才发现大小不对 → 无法再重试
- 只能重新上传

所以先 `listParts`：

- 检查分片是否齐全
- 检查总大小是否等于预期
- 再 complete

`MultipartUploadService` 还有“已完成会话是幂等终点”：

- 重复 complete 直接返回已有 fileId
- 不需要重新合并

### 3.4 断点续传

前端 localStorage 记录：

```text
key = upload:{meetingId}:{fileName}:{size}:{lastModified}
value = { uploadId, completed: [1,2,3] }
```

刷新后：

1. 读本地会话
2. 调 `GET /files/multipart/{uploadId}`（后端 listParts）
3. 以后端已上传分片为准
4. 只补缺失分片
5. complete

run 隔离：

- 每次 upload 捕获独立 runId + AbortController
- 暂停/重置递增 runId
- 旧 run 的响应全部丢弃

### 3.5 ASR 状态机

```text
0 未转写
  → 10 待识别（startAsr 重置旧转写/AI，写 asr_started_at run token）
  → 20 识别中（CreateRecTask 成功，记 asr_task_id）
  → 30 成功（解析+落库）
  → 40 失败（重试耗尽）
```

run token：

- `asr_started_at` 是本次提交的时间
- `markAsrSubmitted` / `markAsrSuccess` / `markAsrRetry` 都带这个 token
- 重新上传/重试会写新 token
- 旧 worker 回来 CAS 不命中，结果丢弃

为什么需要：

- ASR 是异步的，可能 1 分钟到几十分钟
- 用户可能重新上传
- 旧任务的结果不能覆盖新任务

### 3.6 ASR 结果格式

`ResTextFormat=0` 返回 `Result` 文本：

```text
[0:1.030,0:8.570,0]  恩格斯论述了雅典国家。
[0:8.570,0:15.120,1] 罗马国家的情况不同。
```

解析：

```text
[开始,结束,说话人] 文本
```

为什么不用 `ResTextFormat=3`：

- 3 走句级切分，粒度太细
- 38 分钟会议曾产生 958 个碎段
- 0 得到时间戳段落，176 段，接近旧手工导入的 181 段

为什么不用词级时间戳：

- 数据量大、前端渲染成本高
- 段级 + 标点切子句已经够用

### 3.7 语音文本联动

ASR 只给段级时间：

```text
段：[startMs, endMs] + text
```

前端按标点切子句：

```text
"你好，世界。" → ["你好，", "世界。"]
```

子句时间按字符数比例估算：

```text
duration = endMs - startMs
weight = 去掉标点/空白后的字符数
子句 start = 段 start + duration * 已累计权重 / 总权重
子句 end = 段 start + duration * 新累计权重 / 总权重
```

播放时：

- 二分查找当前段
- 再找当前子句
- 高亮当前子句
- `scrollToIndex` 自动滚动
- 点击子句 → `audio.currentTime = startMs / 1000`

### 3.8 虚拟滚动

普通列表 300 段：

- 300 个 DOM 节点
- 每段多行文本 + 时间戳 + 发言人
- 滚动/高亮时重排成本高

`@tanstack/react-virtual`：

- 只渲染视口附近的行（overscan=8）
- 动态行高用 `measureElement` 实测
- 播放高亮时 `scrollToIndex`
- 300 段实测只渲染十几行

### 3.9 DeepSeek 异步

```text
POST /minutes/ai（请求入队）
  → ai_status=10
  → MinutesAiTask 扫表
  → 拼 prompt（转写 + 会议信息）
  → DeepSeek JSON mode
  → 解析/校验
  → summary_json 落库，ai_status=30
```

失败：

- 重试 3 次
- 卡死超时恢复
- mock provider 返回与转写一致的抽取式摘要
- 前端显示 `aiProvider`（本地演示/DeepSeek）

### 3.10 部署拓扑

```text
浏览器 :8000
  → Nginx
     ├── /api/**        → gateway:8080 → auth/meeting/notification
     └── /ws/**         → gateway:8080
  → 4 个 Java 服务 + web 在 smt-net
  → MySQL/Redis/RabbitMQ/Nacos 只在容器网络与回环
```

- compose：9 个常驻容器 + `mysql-init` 一次性任务
- Nginx：`resolver 127.0.0.11` 动态解析；日志不记 query
- CI：backend / frontend / compose-config / docker-build；compose smoke 手动

### 3.11 记忆法：上传、ASR、联动、部署

```text
上传：init → partUrls → 3 并发 PUT → complete（先预检）
ASR：10 提交 → 20 轮询 → 30 落库；run token 防旧覆盖
联动：段级时间 + 标点切句 + 比例估算 + 虚拟滚动
部署：Nginx → gateway → 服务；resolver 防 502
```

---

## 4. 代码走读

### 4.1 后端：分片上传

#### 4.1.1 MultipartUploadService

文件：`server/meeting-service/src/main/java/com/smt/meeting/service/MultipartUploadService.java`

init：

```java
public MultipartInitVO init(MultipartInitDTO dto, Long userId) {
    // 校验会议/权限/文件类型/大小
    String objectKey = buildObjectKey(dto, meetingId);
    ObjectStorage.MultipartInit multipart = storage.initMultipart(objectKey, dto.getContentType());
    FileUploadSession session = new FileUploadSession();
    session.setUploadId(multipart.uploadId());
    session.setObjectKey(objectKey);
    session.setTotalParts(totalParts(dto.getSizeBytes()));
    session.setStatus(0);
    ...
    return new MultipartInitVO(session.getUploadId(), objectKey, ...);
}
```

partUrls：

```java
public List<MultipartPartVO> partUrls(String uploadId, List<Integer> partNumbers, Long userId) {
    FileUploadSession session = loadSession(uploadId, userId);
    for (Integer partNumber : unique(partNumbers)) {
        if (partNumber < 1 || partNumber > session.getTotalParts()) throw ...;
        String url = storage.presignPart(session.getObjectKey(), uploadId, partNumber, ttl);
        result.add(new MultipartPartVO(partNumber, url, expireAt));
    }
    return result;
}
```

complete：

```java
public FileCompleteVO complete(String uploadId, MultipartCompleteDTO dto, Long userId) {
    FileUploadSession session = loadSession(uploadId, userId);
    List<Part> parts = validatedParts(dto, session);

    // 先 listParts 做大小预检（complete 会消费 uploadId）
    List<UploadedPart> uploaded = storage.listParts(session.getObjectKey(), uploadId);
    checkComplete(uploaded, parts, session);

    // 远端调用在 DB 事务外
    storage.completeMultipart(session.getObjectKey(), uploadId, parts);

    // 事务内写 file_object + 更新会话
    return transactionTemplate.execute(tx -> {
        ...
    });
}
```

#### 4.1.2 CosObjectStorage

文件：
- `server/meeting-service/src/main/java/com/smt/meeting/service/CosObjectStorage.java`

COS：

- `initiateMultipartUpload`
- `listParts`
- `completeMultipartUpload`（按 partNumber 排序的 ETag）
- `generatePresignedUrl`（GET/PUT）
- 踩坑：COS SDK 对不可变 `Stream.toList()` 原地排序 → `UnsupportedOperationException`；改传 `ArrayList`

### 4.2 后端：ASR

#### 4.2.1 startAsr

文件：`server/meeting-service/src/main/java/com/smt/meeting/service/MinutesAsrService.java`

```java
public StartResult startAsr(Long meetingId, Long fileId, Long userId) {
    RLock lock = redisson.getLock("lock:minutes:asr:" + meetingId);
    lock.lock();
    try {
        Meeting meeting = requireFinishedMeeting(meetingId);
        requireAudioFile(meetingId, fileId);
        MeetingMinutes current = minutesMapper.selectByMeetingId(meetingId);
        if (current != null) {
            // 同文件且 10/20：幂等返回
            if ((status == PENDING || status == RUNNING) && fileId.equals(current.getAudioFileId())) {
                return new StartResult(current.getId(), null);
            }
            current = minutesMapper.selectByMeetingIdForUpdate(meetingId);
            Long oldFileId = current.getAudioFileId();
            minutesMapper.startAsr(current.getId(), fileId, 0L, now());
            speakerMapper.deleteByMinutesId(current.getId());
            segmentMapper.deleteByMinutesId(current.getId());
            return new StartResult(current.getId(), oldFileId);
        }
        // 新建 minutes + startAsr
    } finally {
        lock.unlock();
    }
}
```

- 会议锁：防并发 start
- `startAsr` 写 `asr_status=10` + `asr_started_at=now()`（run token）
- 清空旧 speaker/segment
- 同文件且 10/20 幂等返回

#### 4.2.2 process/submit/poll

```java
public void process(Long minutesId) {
    MeetingMinutes minutes = minutesMapper.selectById(minutesId);
    int status = ...;
    if (status == PENDING) submit(minutes);
    else if (status == RUNNING) poll(minutes);
}

private void submit(MeetingMinutes minutes) {
    FileObject file = requireAudioFile(...);
    String url = storage.presignGet(file.getObjectKey(), Duration.ofHours(6));
    String taskId = asrClient.submit(url);
    LocalDateTime startedAt = minutes.getAsrStartedAt();
    if (minutesMapper.markAsrSubmitted(minutes.getId(), taskId, startedAt) == 0) {
        log.warn("markAsrSubmitted CAS 未命中");
    }
}

private void poll(MeetingMinutes minutes) {
    AsrQueryResult result = asrClient.query(minutes.getAsrTaskId());
    if (WAITING/PROCESSING) return;
    if (FAILED) handleFailure(...);
    if (empty) handleFailure(...);
    persistSuccess(minutes, result);
}
```

#### 4.2.3 persistSuccess

```java
private void persistSuccess(MeetingMinutes minutes, AsrQueryResult result) {
    LocalDateTime startedAt = minutes.getAsrStartedAt();
    boolean saved = transactionTemplate.execute(status -> {
        MeetingMinutes locked = minutesMapper.selectByMeetingIdForUpdate(minutes.getMeetingId());
        if (locked == null
            || locked.getAsrStatus() != RUNNING
            || !Objects.equals(locked.getAsrStartedAt(), startedAt)) {
            return false; // 旧任务/新一轮，丢弃
        }
        long durationMs = max(result.durationMs(), lastEnd);
        if (minutesMapper.markAsrSuccess(locked.getId(), durationMs, now(), startedAt) == 0) {
            return false;
        }
        speakerMapper.deleteByMinutesId(locked.getId());
        segmentMapper.deleteByMinutesId(locked.getId());
        insertSpeakers(locked.getId(), result.sentences());
        insertSegments(locked.getId(), result.sentences());
        return true;
    });
    if (!saved) log.warn("结果被丢弃（重新上传/新一轮）");
}
```

关键：

- 锁 minutes 行
- 校验 `asr_status=20 && asr_started_at=startedAt`
- `markAsrSuccess` 也带 run token CAS
- 删旧 speaker/segment 再插入

#### 4.2.4 TencentAsrClient

文件：`server/meeting-service/src/main/java/com/smt/meeting/service/TencentAsrClient.java`

```java
CreateRecTaskRequest request = new CreateRecTaskRequest();
request.setEngineModelType("16k_zh");
request.setChannelNum(1L);
request.setResTextFormat(0L);
request.setSourceType(0L);         // URL
request.setSpeakerDiarization(1L);
request.setSourceUrl(url);
CreateRecTaskResponse response = sdk.CreateRecTask(request);
```

- 异步任务，返回 TaskId
- `DescribeTaskStatus` 轮询
- 状态 0/1/2/3 → WAITING/PROCESSING/SUCCESS/FAILED
- 优先解析 `TaskStatus.Result`，回退 `ResultDetail`
- 不打印 URL/密钥/转写正文

#### 4.2.5 AsrResultParser

文件：`server/meeting-service/src/main/java/com/smt/meeting/service/AsrResultParser.java`

```java
private static final Pattern LINE = Pattern.compile(
    "^\\[(?:(\\d{1,6}):)?(\\d{1,6}):(\\d{1,2}(?:\\.\\d{1,3})?),"
  + "(?:(\\d{1,6}):)?(\\d{1,6}):(\\d{1,2}(?:\\.\\d{1,3})?),(\\d{1,32})\\]\\s*(.*)$");

for (String raw : result.split("\\R")) {
    Matcher m = LINE.matcher(raw.strip());
    if (!m.matches()) continue;
    Long start = parseTime(m.group(1), m.group(2), m.group(3));
    Long end = parseTime(m.group(4), m.group(5), m.group(6));
    String text = m.group(8).strip();
    if (start == null || end == null || end < start || text.isEmpty()) continue;
    sentences.add(new Sentence(text, start, end, m.group(7)));
}
```

- 支持 `m:ss.SSS` / `h:mm:ss.SSS`
- 时间越界/倒序/空文本跳过
- 供应商字段按不可信输入处理

#### 4.2.6 MinutesAsrTask

文件：`server/meeting-service/src/main/java/com/smt/meeting/task/MinutesAsrTask.java`

- 每 5 秒扫待提交（10）、识别中（20）、卡死（20 超 120 分钟）
- 任务锁只包住“选 id”，条目锁包住单条处理
- 调度器 4 线程，避免 AI/ASR/清理互相饿死

### 4.3 后端：AI 纪要

文件：
- `server/meeting-service/src/main/java/com/smt/meeting/service/DeepSeekLlmClient.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/MinutesAiService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/task/MinutesAiTask.java`

```java
public String completeJson(String systemPrompt, String userPrompt) {
    // OpenAI 兼容 Chat Completions
    // response_format = { type: "json_object" }
    // 超时/重试
}
```

`MinutesAiService`：

- `request`：校验转写存在、冷却时间、状态 → `ai_status=10`
- `MinutesAiTask` 异步扫 10/20/卡死
- `process`：拼 prompt → `completeJson` → `MinutesSummaryParser` 校验 → `summary_json`
- 失败重试/卡死恢复
- mock provider 从转写抽取摘要，前端显示“本地演示”

### 4.4 前端：上传与工作台

#### 4.4.1 useMultipartUpload

文件：`web/src/hooks/useMultipartUpload.ts`

关键：

- 3 并发 worker
- 每片失败 1s/2s/4s 退避，最多 4 次
- `AbortController` 暂停在途请求
- localStorage 记录 uploadId + completed
- 刷新后 listParts 续传
- runId 隔离：暂停/重置后旧 run 响应丢弃
- complete 返回 fileId

#### 4.4.2 MinutesUploader

文件：`web/src/components/minutes/MinutesUploader.tsx`

```ts
async function browserCanPlay(file: File): Promise<boolean> {
  const url = URL.createObjectURL(file)
  const audio = document.createElement('audio')
  audio.src = url
  return new Promise((resolve) => {
    audio.onloadedmetadata = () => resolve(true)
    audio.onerror = () => resolve(false)
  })
}
```

- 只接受音频
- 上传前预检浏览器可播性，ALAC 直接拦截
- `useMultipartUpload` 上传
- 成功后 `startTranscription(meetingId, fileId)`
- 支持暂停/继续；会话过期重新 init

#### 4.4.3 AudioTranscriptWorkspace

文件：`web/src/components/minutes/AudioTranscriptWorkspace.tsx`

- `asrStatus 10/20`：正在识别，轮询详情
- `asrStatus 40`：失败提示 + 重新上传
- `asrStatus 30` 或历史 0：拉 transcript
- 音频 URL 过期自动重签
- `audioRef` + `findActiveSentence` 高亮
- 请求 token 防旧响应覆盖新状态

#### 4.4.4 TranscriptList

文件：`web/src/components/minutes/TranscriptList.tsx`

```tsx
const virtualizer = useVirtualizer({
  count: segments.length,
  getScrollElement: () => parentRef.current,
  estimateSize: () => 72,
  overscan: 8,
})

useEffect(() => {
  if (activeIndex >= 0) {
    virtualizer.scrollToIndex(activeIndex, { align: 'auto', behavior: 'smooth' })
  }
}, [activeIndex])

<div ref={virtualizer.measureElement} ...>
```

- `measureElement` 动态行高
- 每段按标点切子句，当前子句高亮
- 点击子句 → `onSeek(startMs)`

#### 4.4.5 transcriptSync

文件：`web/src/utils/transcriptSync.ts`

```ts
const SENTENCE_PATTERN = /[^，。！？；,;!?…]+[，。！？；,;!?…]*|[，。！？；,;!?…]+/gu

export function splitSentences(text: string): string[] {
  if (!text || !text.trim()) return []
  return (text.match(SENTENCE_PATTERN) ?? []).map(s => s.trim()).filter(Boolean)
}

export function splitSegmentIntoSentences(segment: TimedSegment & { text: string }) {
  const sentences = splitSentences(segment.text)
  const weights = sentences.map(s => Math.max(1, Array.from(s.replace(/[，。！？；,;!?…\s]/gu, '')).length))
  const totalWeight = weights.reduce((a, b) => a + b, 0)
  const duration = Math.max(0, segment.endMs - segment.startMs)
  let elapsed = 0
  return sentences.map((text, index) => {
    const startMs = segment.startMs + Math.round((duration * elapsed) / totalWeight)
    elapsed += weights[index]
    const endMs = index === sentences.length - 1
      ? segment.endMs
      : segment.startMs + Math.round((duration * elapsed) / totalWeight)
    return { text, startMs, endMs: Math.max(startMs, endMs) }
  })
}
```

- 标点保留在前一句
- 时间按字符权重比例估算
- `findActiveSentence` 二分查找当前段/子句

#### 4.4.6 MinutesSummary / Insights

文件：
- `web/src/components/minutes/MinutesSummary.tsx`
- `web/src/components/minutes/MinutesInsights.tsx`

- Summary：摘要/关键结论/决策/待办/风险
- Insights：时长/覆盖率/发言人数/发言人时长
- AI 状态条：0/10/20/30/40 + 重试
- mock 模式显示“本地演示”

### 4.5 部署与 CI

#### 4.5.1 Dockerfile

- 4 个后端：Maven 多阶段 + BuildKit cache + 非 root
- web：node 构建 → nginx 托管
- `.dockerignore` 排除本地密钥配置、嵌套 `.env`
- 构建期自检：`find` 本地配置，发现即失败

#### 4.5.2 compose

- infra 4 + 服务 4 + web 1
- `mysql-init` 一次性建 `smt_app`，每次 up 同步密码
- 健康检查：MySQL 查种子用户、Redis/RabbitMQ/Nacos
- gateway/web 默认只绑 `127.0.0.1`
- 日志轮转：10m × 3

#### 4.5.3 Nginx

```nginx
resolver 127.0.0.11 valid=10s ipv6=off;
set $gateway gateway:8080;
set $meeting meeting-service:8082;

location /api/ { proxy_pass http://$gateway; ... }
location /ws/ { proxy_pass http://$gateway; Upgrade ... }
location / { try_files $uri $uri/ /index.html; }
```

- 动态解析：服务容器重建换 IP 不用重启 web
- 访问日志只记 `$uri`，不落 WS token/预签名 query
- SPA fallback

#### 4.5.4 CI

`.github/workflows/ci.yml`：

- backend：MySQL/Redis/RabbitMQ service containers + `mvn test`
- frontend：Node 20 + `npm ci` + `npm run check` + `npm run build`
- compose-config：`docker compose config --quiet`
- docker-build：构建全部镜像 + `nginx -t` + jar 密钥扫描
- compose-smoke：手动触发，起全栈 + `verify-deploy.sh`

`verify-deploy.sh` 14 项：

- 首页/深层路由/登录/会议室/会议/Nacos/WS

### 4.6 边界与异常

| 场景 | 行为 | 说明 |
|------|------|------|
| 音频 ALAC | 上传前拦截 | Chrome 不支持 |
| complete 前大小不符 | 拒绝 | 避免消费 uploadId |
| 重复 complete | 幂等返回 fileId | 已完成会话 |
| 上传会话过期 | 重新 init | 前端提示 |
| ASR 旧任务回来 | run token CAS 不命中 | 丢弃 |
| ASR 查询抖动 | 保持 20，超时重试 | 卡死扫描 |
| ASR 空结果 | 失败/重试 | 未识别到语音 |
| AI 失败 | 重试/失败状态 | 可重新生成 |
| AI mock | “本地演示”标签 | 不冒充真实模型 |
| 音频 URL 过期 | 自动重签 | 预签名 |
| Nginx 502 | 检查 resolver/上游 | 动态解析 |
| 容器重建 | 数据卷保留 | 命名卷 |

### 4.7 📘 笔记引用

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

### 4.8 举一反三

#### 4.8.1 分片上传还能用在哪

- 视频/大文件上传
- 数据导入/导出
- 日志/备份上传
- 断点续传下载

套路：

```text
预签名 + 分片 + 并发 + ETag + 续传 + 大小预检
```

#### 4.8.2 外部 API 状态机

ASR 这类异步 API 的通用模式：

```text
提交任务 → 返回 taskId
轮询/回调 → 状态
成功 → 解析/落库
失败 → 重试/死信
run token → 防旧任务覆盖
```

可迁移到：

- 视频转码
- 图片审核
- 支付回调
- 物流轨迹
- AI 生成

#### 4.8.3 虚拟列表

- 聊天记录
- 日志流
- 大表格
- 通知列表
- 转写/字幕

关键：

- 动态行高 `measureElement`
- overscan
- `scrollToIndex`
- 不要用 index 当 key（如果会重排）

#### 4.8.4 部署排障

| 现象 | 排查 |
|------|------|
| 502 | Nginx resolver/上游端口/容器状态 |
| 401 | token/白名单/secret 一致性 |
| 403 | 角色/归属 |
| 上传失败 | CORS/预签名/分片大小/ETag |
| 音频不能播 | 编码/Content-Type/预签名 URL |
| 容器 unhealthy | 健康检查/日志/依赖 |
| CI 失败 | 看 job/日志/本地复现 |

#### 4.8.5 反例

- 大文件走后端 → 线程/内存打满
- complete 前不预检 → uploadId 被消费，无法重试
- ASR 不用 run token → 旧任务覆盖新转写
- 用 `ResTextFormat=3` → 958 个碎段
- 虚拟列表假设固定行高 → 文本换行错位
- Nginx 启动时解析上游 → 容器重建 502

---

## 5. 手敲练习

### 5.1 ASR 解析测试

在 `server/meeting-service/src/test/java/com/smt/meeting/service/AsrResultParserTest.java` 增加：

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

### 5.2 splitSentences 边界测试

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

验收：边界用例通过；能解释“时间是按字符数比例估算”。

### 5.3 部署验收

```bash
bash scripts/verify-deploy.sh
```

Expected: `通过 15，失败 0`（COS 模式加 `CHECK_LOCAL_FILES=0`）。

### 5.4 15 分钟演示

按 `docs/08-面试演示与高频追问.md` 走一遍：

- 0:00–1:30 项目定位
- 1:30–3:30 架构
- 3:30–6:30 并发预约
- 6:30–8:30 审批/状态机
- 8:30–11:30 会议纪要
- 11:30–13:30 部署/CI
- 13:30–15:00 取舍/踩坑

验收：不看稿能讲完；每个模块能落到 1-2 个文件和 1 个取舍。

### 5.5 破坏性实验（学习分支）

- 把 `ResTextFormat` 改成 3 → 观察碎段
- 去掉 `asr_started_at` 校验 → 模拟旧任务覆盖
- 把 `TranscriptList` 的虚拟滚动换成全量渲染 → 300 段 DOM 数
- 把 Nginx 的 `resolver` 去掉，重建 gateway → 观察 502
- 把音频换成 ALAC → 观察浏览器不能播

### 5.6 变体：加一个“导出 Markdown”

需求：除了 Word，还能导出 Markdown。

- `MinutesExportService` 加 `exportMarkdown`
- Controller 加接口
- 前端下载按钮加格式选择
- 测试：内容与 summary 一致

验收：下载文件扩展名/内容正确。

---

## 6. 八道自测

### 题目

1. 预签名 URL 为什么不让文件经过后端？
2. ASR run token 解决什么问题？
3. `ResTextFormat=0` 和 `3` 的区别？
4. complete 前为什么要 listParts 预检？
5. 虚拟滚动为什么能减少 DOM？动态行高怎么处理？
6. DeepSeek 生成是同步还是异步？失败/卡死怎么恢复？
7. 容器重建后 Nginx 怎么避免 502？CI 的四个 job 分别做什么？
8. 音频上传后浏览器不能播放，怎么排查？

### 答案

1. 文件字节不经过后端，避免大文件占用 Tomcat 线程/内存；后端只签名和管元数据；分片 + 断点续传解决大文件和弱网。
2. 重新上传/重试会写新的 `asr_started_at`；旧 worker 回来 CAS 不命中，结果丢弃，不会覆盖新一轮转写。
3. `0` 返回带时间戳的段落文本（`[start,end,speaker] text`），更接近旧版体验；`3` 走句级切分，粒度太细（曾产生 958 个碎段）。
4. `completeMultipartUpload` 会消费 uploadId；合并后才发现大小不符无法重试，所以先 listParts 校验分片齐全和总大小。
5. 只渲染视口附近的行，DOM 数量从 N 降到十几；动态行高用 `measureElement` 实测，不假设固定高度。
6. 异步：`ai_status=10` 入队，`MinutesAiTask` 扫表生成；失败重试、卡死超时恢复；mock provider 可选。
7. Nginx 用 `resolver 127.0.0.11` + 变量 `proxy_pass`，在 valid 周期内重新解析服务名，不缓存旧 IP；CI 四个 job：backend/frontend/compose-config/docker-build，compose-smoke 手动。
8. 先看浏览器 `error.code`/`canPlayType`：ALAC 不支持 → 转 AAC/M4A/MP3；再看 Content-Type、预签名 URL、CORS、Range。

### 追问

- 如果 ASR 回调而不是轮询，状态机怎么改？
- 如果分片上传中断在 complete 中途，怎么恢复？
- 如果 AI 生成很慢，前端怎么设计状态？
- 如果 Nginx 多实例，resolver 和 session 共享怎么处理？

---

## 7. 复盘卡

### 7.1 三句话

```text
1. 大文件直传 COS：后端签名，前端分片，complete 前做大小预检。
2. ASR 用 run token + 状态机，旧任务回来不会覆盖新一轮。
3. 语音文本联动 = 段级时间戳 + 标点切子句 + 比例估算 + 虚拟滚动。
```

### 7.2 归纳：会议纪要一张表

| 阶段 | 组件 | 关键动作 | 失败 |
|------|------|----------|------|
| 上传 | MultipartUploadService | init/partUrls/complete | 70006/分片缺失 |
| ASR | MinutesAsrService | 提交/轮询/落库 | 重试/失败 |
| 联动 | transcriptSync | 切句/估算/二分 | 空段/乱序 |
| AI | MinutesAiService | 入队/生成/校验 | 重试/卡死 |
| 导出 | MinutesExportService | Word 生成 | 模板/IO |
| 部署 | Docker/Nginx/CI | 构建/反代/验收 | 502/401/上传 |

### 7.3 演绎：从原则推导新场景

原则：**大文件不经过应用层；异步任务用状态机 + run token；前端只渲染可见部分**。

由此推导：

- 为什么预签名直传？——应用层不搬字节
- 为什么 complete 前预检？——远端合并不好回滚
- 为什么 ASR 用 run token？——异步结果可能过期
- 为什么虚拟滚动？——DOM 数量是渲染成本
- 为什么 Nginx 动态解析？——容器 IP 会变

### 7.4 记忆法

```text
上传：init → partUrls → 3 并发 PUT → complete（先预检）
ASR：10 提交 → 20 轮询 → 30 落库；run token 防旧覆盖
联动：段级时间 + 标点切句 + 比例估算 + 虚拟滚动
部署：Nginx → gateway → 服务；resolver 防 502
```

### 7.5 60 秒面试话术

> 会议纪要的音频走预签名直传：后端只做 init/partUrls/complete，前端 3 并发分片上传，
> complete 前 listParts 做大小预检，支持断点续传。上传后提交腾讯 ASR 异步任务，
> 用 status 0/10/20/30/40 和 asr_started_at run token 防旧任务覆盖；
> ResTextFormat=0 解析时间戳段落，避免句级切分产生大量碎段。
> 前端用段级时间 + 标点切子句 + 字符比例估算做逐句高亮，虚拟滚动只渲染可见行。
> 纪要生成是 DeepSeek 异步 worker；部署是 compose + Nginx + GitHub Actions。

### 7.6 面试彩排清单

- [ ] 15 分钟不看稿
- [ ] 每个模块 1-2 个文件 + 1 个取舍
- [ ] 能回答 `docs/08` 的 20+ 追问
- [ ] 演示前 `verify-deploy` 14/14
- [ ] 备用：网络不稳时 ASR/AI 切 mock；COS 上传提前验证

---

### 附：今天要能打开的文件

- `server/meeting-service/src/main/java/com/smt/meeting/service/MultipartUploadService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/CosObjectStorage.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/MinutesAsrService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/TencentAsrClient.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/AsrResultParser.java`
- `server/meeting-service/src/main/java/com/smt/meeting/task/MinutesAsrTask.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/DeepSeekLlmClient.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/MinutesAiService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/service/MinutesService.java`
- `server/meeting-service/src/main/java/com/smt/meeting/controller/MinutesController.java`
- `web/src/components/minutes/MinutesUploader.tsx`
- `web/src/components/minutes/AudioTranscriptWorkspace.tsx`
- `web/src/components/minutes/TranscriptList.tsx`
- `web/src/utils/transcriptSync.ts`
- `web/src/components/minutes/MinutesSummary.tsx`
- `web/src/components/minutes/MinutesInsights.tsx`
- `web/src/hooks/useMultipartUpload.ts`
- `web/src/components/minutes/minutesUi.ts`
- `deploy/docker-compose.yml`
- `deploy/nginx.conf`
- `.github/workflows/ci.yml`
- `scripts/verify-deploy.sh`
