# web/e2e —— 前端端到端验收脚本

这三个脚本是 **T2.F1 / T2.F2 / T2.F3 任务验收标准的可执行版本**（docs/05 里"X/X 通过"的数字就是它们跑出来的）。
放在仓库里是为了让验收可复现、可审查——之前它们只存在于本地临时目录，文档里的数字对别人是空的。

## 前置

1. 后端三服务 + 基础设施已启动（见根 `CLAUDE.md` §7）
2. 前端 dev server 在 `http://localhost:5173`（`npm run dev`）
3. 装 playwright（**不随仓库依赖分发**，只跑 e2e 时才需要）：

```bash
cd web && npm i -D playwright      # 一次即可；用本机 Chrome，不额外下载浏览器
```

## 运行

```bash
cd web
node e2e/e2e-calendar.cjs       # T2.F1：切周加载 / 会议落格 / 点空格带出预填时间
node e2e/e2e-booking.cjs        # T2.F2：创建+刷新日历 / 冲突提示与弹窗 / 幂等只落一场
node e2e/e2e-meeting-list.cjs   # T2.F3：筛选分页 / 状态时间线 / 取消 → 50 / 取消按钮仅组织人
node e2e/e2e-approval.cjs       # T3.F1+F2：审批中心（待办→通过→已办）+ 详情页延长/提前结束
node e2e/e2e-meeting-minutes.cjs # M6：上传音频+转写 → 联动 seek → 洞察 → AI → Word 下载 → 300 段虚拟滚动
```

> ⚠️ `e2e-meeting-minutes.cjs` 要求 meeting-service 已启动：CI 可用
> `STORAGE_PROVIDER=local MINUTES_LOCAL_DIR=/tmp/smt-minutes-test`，真实桶用
> `STORAGE_PROVIDER=cos` + `COS_*`（桶需允许前端 origin 的 PUT/GET/HEAD 并暴露 ETag）。
> 音频需为浏览器支持的编码（AAC/M4A、MP3、WAV）；ALAC 会报 `MEDIA_ERR_SRC_NOT_SUPPORTED`。
> AI 可用 `MINUTES_AI_PROVIDER=mock`（断言摘要来自转写）或 `deepseek`（断言行文非空）；
> `verify-minutes-ai.sh` 的失败注入仍要求 mock。
> COS 模式下脚本只清理 MySQL/本地目录，不会删除桶里的测试对象，需按 `minutes/{meetingId}/` 手工清理。
>
> ⚠️ `e2e-approval.cjs` 比前三个慢（约 4 分钟）：它的后半段要等一场会议**真的到点**，
> 由定时任务推进到"进行中"，才能验证延长/提前结束——所以跑它时**定时任务必须开着**
> （`meeting.task.status.enabled` 默认 true；跑完 `verify-m3-scheduler.sh` 后记得把服务按默认间隔重启）。

`e2e-notify-ws.cjs` 与其它几个不同：它**不点界面**，而是从真实前端源（`http://localhost:5173`）起页面、
在里面直接开 WebSocket，验证"网关 `?token=` 鉴权 → 握手 → 秒级推送 → 会话注销"整条链路。
⚠️ 它必须从**真实前端源**起页面：`about:blank` 会被 Chrome 的本地网络访问保护拦掉
（`ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS_CHECKS`），而且会让"不带 token 连不上"这条断言假阳性通过。

退出码 `0` = 全部断言通过，非 0 = 有失败（可直接接 CI）。

## 约定（踩过的坑，改脚本前先看）

- **可重复运行**：每个脚本先调 `availability` **预检挑空闲时段**，跑完**自动取消**本轮造出来的会议。
  不这么做的话，几轮之后时段被自己填满，预检失败会被误读成"功能坏了"。
- **不要点"已经过去的钟点"**：后端会以 `30003 不可预约过去的时间` 拒绝，脚本里用"本周六/周日"这类未来日期。
- **不要点会议块所在的像素位置**：块上的点击是"看详情"且会 `stopPropagation`（这是设计），
  抽屉不会开。要点格子里的**空白高度**。
- **断言尽量用角色/结构定位，别用整页文本包含**：详情页时间线里就有"取消会议"这几个字，
  用 `!body.includes('取消会议')` 断言"看不到取消按钮"必然误伤。
- 登录态由页面自己走登录流程建立；需要直连接口做机制级断言时，token 的 localStorage key 是
  `meeting.accessToken`（见 `src/api/token.ts`）。

> 另有若干更早的本地脚本（登录/守卫/刷新 single-flight/会议室管理，对应 T1.F3~T1.F4）只在开发机上，
> 未入库；若要纳入 CI，按上面同样约定补进来即可。
