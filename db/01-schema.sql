-- ============================================================================
-- 会议室预约系统 · 数据库结构（单库 smt_meeting）
-- 对应文档：docs/01-需求文档.md（v1.2）/ docs/02-技术架构方案.md（v1.1）
-- 环境要求：MySQL 8.0+（使用 utf8mb4_0900_ai_ci 排序规则）
-- 执行方式：mysql -h127.0.0.1 -P3306 -uroot -p < 01-schema.sql
-- 设计约定：
--   1) 主键统一 BIGINT 自增；时间统一 DATETIME（不用 TIMESTAMP，避免 2038/时区坑）
--   2) 状态值用 TINYINT 数字（与状态机约定一致）；角色/类型用 VARCHAR 枚举字符串
--   3) 不使用外键约束（关系由应用层维护，理由见 03 文档第五章）
--   4) 不使用逻辑删除（会议室/用户用 status 停用；会议用 status=取消）
--   5) 所有表带 created_at / updated_at 审计字段
-- ============================================================================

-- 强制本会话字符集为 utf8mb4（关键：防止 initdb 阶段容器内客户端默认字符集
-- 非 utf8mb4 时，中文按 latin1 解释导致"双重编码"乱码）
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS smt_meeting
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE smt_meeting;

-- ----------------------------------------------------------------------------
-- 1. sys_user 用户表
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  username    VARCHAR(50)  NOT NULL                COMMENT '登录名',
  password    VARCHAR(100) NOT NULL                COMMENT 'BCrypt 密码哈希',
  real_name   VARCHAR(50)  NOT NULL                COMMENT '姓名',
  email       VARCHAR(100) DEFAULT NULL            COMMENT '邮箱（通知用）',
  avatar      VARCHAR(255) DEFAULT NULL            COMMENT '头像 URL（腾讯云 COS）',
  dept_id     BIGINT       DEFAULT NULL            COMMENT '所属部门（sys_dept.id，可空=未分配）',
  status      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：0 禁用 / 1 启用',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username),
  KEY idx_dept (dept_id)
) ENGINE = InnoDB COMMENT = '用户表';

-- ----------------------------------------------------------------------------
-- 1.1 sys_dept 部门表（树形结构；用途仅"组织结构"，不参与权限判定）★ v1.3
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_dept (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '部门ID',
  parent_id   BIGINT       NOT NULL DEFAULT 0      COMMENT '父部门ID（0=根）',
  name        VARCHAR(50)  NOT NULL                COMMENT '部门名称',
  sort_order  INT          NOT NULL DEFAULT 0      COMMENT '同级排序',
  status      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：0 停用 / 1 启用',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_parent (parent_id)
) ENGINE = InnoDB COMMENT = '部门表（树形）';

-- ----------------------------------------------------------------------------
-- 1.2 sys_role 角色表 ★ v1.3（替代原 sys_user.role 单字段，支持多角色）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_role (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  code        VARCHAR(30)  NOT NULL                COMMENT '角色编码：USER/APPROVER/ADMIN（鉴权按 code）',
  name        VARCHAR(50)  NOT NULL                COMMENT '角色名称',
  description VARCHAR(200) DEFAULT NULL            COMMENT '描述',
  status      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：0 停用 / 1 启用',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code)
) ENGINE = InnoDB COMMENT = '角色表';

-- ----------------------------------------------------------------------------
-- 1.3 sys_permission 权限表（资源清单：当前不参与鉴权，供菜单/后续扩展）★ v1.3
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_permission (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '权限ID',
  code        VARCHAR(50)  NOT NULL                COMMENT '权限标识（如 room:manage）',
  name        VARCHAR(50)  NOT NULL                COMMENT '权限名称',
  type        TINYINT      NOT NULL DEFAULT 1      COMMENT '类型：1 菜单 / 2 按钮 / 3 接口',
  sort_order  INT          NOT NULL DEFAULT 0      COMMENT '排序',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code)
) ENGINE = InnoDB COMMENT = '权限表（资源清单）';

-- ----------------------------------------------------------------------------
-- 1.4 sys_user_role 用户角色关联（N:N 中间表）★ v1.3
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user_role (
  id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id     BIGINT   NOT NULL                COMMENT '用户ID',
  role_id     BIGINT   NOT NULL                COMMENT '角色ID',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_role (user_id, role_id),
  KEY idx_role (role_id)
) ENGINE = InnoDB COMMENT = '用户角色关联表';

-- ----------------------------------------------------------------------------
-- 1.5 sys_role_permission 角色权限关联（N:N 中间表）★ v1.3
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_role_permission (
  id            BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  role_id       BIGINT   NOT NULL                COMMENT '角色ID',
  permission_id BIGINT   NOT NULL                COMMENT '权限ID',
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_permission (role_id, permission_id),
  KEY idx_permission (permission_id)
) ENGINE = InnoDB COMMENT = '角色权限关联表';

-- ----------------------------------------------------------------------------
-- 2. room_group 会议室分组（楼层/区域）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS room_group (
  id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '分组ID',
  name        VARCHAR(50) NOT NULL                COMMENT '分组名（如：3F 东区）',
  sort_order  INT         NOT NULL DEFAULT 0      COMMENT '排序值（越小越靠前）',
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT = '会议室分组表';

-- ----------------------------------------------------------------------------
-- 3. meeting_room 会议室
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS meeting_room (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会议室ID',
  name        VARCHAR(50)  NOT NULL                COMMENT '会议室名称',
  group_id    BIGINT       NOT NULL                COMMENT '分组ID（room_group.id）',
  capacity    INT          NOT NULL DEFAULT 0      COMMENT '容纳人数',
  location    VARCHAR(100) DEFAULT NULL            COMMENT '具体位置（如：3F-301）',
  images      JSON         DEFAULT NULL            COMMENT '图片 URL 数组（COS）',
  device_tags JSON         DEFAULT NULL            COMMENT '设备标签数组，如 ["投影仪","视频会议"]',
  status      TINYINT      NOT NULL DEFAULT 0      COMMENT '状态：0 可用 / 1 维护中',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_group (group_id)
) ENGINE = InnoDB COMMENT = '会议室表';

-- ----------------------------------------------------------------------------
-- 4. room_strategy 预约策略（1:1 于会议室）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS room_strategy (
  id                      BIGINT   NOT NULL AUTO_INCREMENT COMMENT '策略ID',
  room_id                 BIGINT   NOT NULL                COMMENT '会议室ID（唯一）',
  need_approval           TINYINT  NOT NULL DEFAULT 0      COMMENT '是否需审批：0 免审 / 1 需审批',
  advance_days            INT      NOT NULL DEFAULT 30     COMMENT '最多提前预约天数',
  max_duration_minutes    INT      NOT NULL DEFAULT 240    COMMENT '单场最长时长（分钟）',
  cancel_deadline_minutes INT      NOT NULL DEFAULT 0      COMMENT '取消截止：距开始不足 N 分钟不可取消',
  buffer_minutes          INT      NOT NULL DEFAULT 0      COMMENT '锁定缓冲：会议前后各 N 分钟不排会（R-13）',
  allow_recurring         TINYINT  NOT NULL DEFAULT 1      COMMENT '是否允许周期会议：0 否 / 1 是',
  created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_room (room_id)
) ENGINE = InnoDB COMMENT = '会议室预约策略表';

-- ----------------------------------------------------------------------------
-- 5. meeting 会议主表（核心）
-- 状态机：10 待审批 / 20 已预约 / 30 进行中 / 40 已结束 / 50 已取消 / 60 已拒绝
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS meeting (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会议ID',
  title         VARCHAR(100) NOT NULL                COMMENT '会议主题',
  room_id       BIGINT       NOT NULL                COMMENT '会议室ID',
  organiser_id  BIGINT       NOT NULL                COMMENT '组织人ID（sys_user.id）',
  start_time    DATETIME     NOT NULL                COMMENT '开始时间',
  end_time      DATETIME     NOT NULL                COMMENT '结束时间',
  status        TINYINT      NOT NULL                COMMENT '状态：10 待审批/20 已预约/30 进行中/40 已结束/50 已取消/60 已拒绝',
  remark        VARCHAR(500) DEFAULT NULL            COMMENT '备注',
  cancel_reason VARCHAR(200) DEFAULT NULL            COMMENT '取消原因',
  cancel_time   DATETIME     DEFAULT NULL            COMMENT '取消时间',
  recurring_id  BIGINT       DEFAULT NULL            COMMENT '周期系列ID（NULL=非周期会议，P1）',
  version       INT          NOT NULL DEFAULT 0      COMMENT '乐观锁版本号（修改会议时校验）',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  -- 冲突检测核心索引：room_id 等值 + start_time 范围（覆盖 end_time 减少回表）
  KEY idx_room_time (room_id, start_time, end_time),
  -- 定时任务：开始流转（status=20 AND start_time<=now）
  KEY idx_status_start (status, start_time),
  -- 定时任务：结束流转（status IN (20,30) AND end_time<=now）
  KEY idx_status_end (status, end_time),
  -- 我的会议列表（organiser_id 等值 + start_time 排序）
  KEY idx_organiser (organiser_id, start_time),
  -- 周期系列查询（T5.2）：WHERE recurring_id = ? [AND start_time > ?] ORDER BY start_time
  -- 没有它时 EXPLAIN 是 type=ALL 全表扫（实测：89 行的小表也已全扫）；加上后 type=ref + Using index
  KEY idx_recurring (recurring_id, start_time)
) ENGINE = InnoDB COMMENT = '会议主表';

-- ----------------------------------------------------------------------------
-- 6. meeting_participant 参会人
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS meeting_participant (
  id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  meeting_id  BIGINT   NOT NULL                COMMENT '会议ID',
  user_id     BIGINT   NOT NULL                COMMENT '参会人用户ID（限系统用户；v1.3 起不区分外部参会人）',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
  PRIMARY KEY (id),
  -- N:N 中间表标配：联合唯一防重复参会人；其最左前缀覆盖"按会议查参会人"，故不再单建 idx_meeting
  UNIQUE KEY uk_meeting_user (meeting_id, user_id),
  KEY idx_user (user_id)
) ENGINE = InnoDB COMMENT = '会议参会人表（N:N 中间表）';

-- ----------------------------------------------------------------------------
-- 7. meeting_approval 审批记录（历史表，同一会议可多条：被拒后修改重送审场景）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS meeting_approval (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  meeting_id  BIGINT       NOT NULL                COMMENT '会议ID',
  approver_id BIGINT       NOT NULL                COMMENT '审批人ID',
  action      VARCHAR(10)  NOT NULL                COMMENT '动作：PASS 通过 / REJECT 拒绝',
  `comment`   VARCHAR(500) DEFAULT NULL            COMMENT '审批意见（拒绝时必填）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '审批时间',
  PRIMARY KEY (id),
  KEY idx_meeting (meeting_id),
  -- 审批中心「已处理」列表：WHERE approver_id = ? ORDER BY created_at DESC（T3.1）
  -- 没有它时该查询是 ALL + filesort（T3.1 实测 EXPLAIN），加索引后为 ref/range 且天然有序
  KEY idx_approver_time (approver_id, created_at)
) ENGINE = InnoDB COMMENT = '会议审批记录表';

-- ----------------------------------------------------------------------------
-- 8. notification 站内通知
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification (
  id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '通知ID',
  user_id    BIGINT       NOT NULL                COMMENT '接收人ID',
  type       VARCHAR(30)  NOT NULL                COMMENT '类型：MEETING_CREATED/APPROVAL_TODO/APPROVAL_RESULT/MEETING_REMIND/MEETING_UPDATED/MEETING_CANCELLED',
  title      VARCHAR(100) NOT NULL                COMMENT '标题',
  content    VARCHAR(500) NOT NULL                COMMENT '内容',
  meeting_id BIGINT       DEFAULT NULL            COMMENT '关联会议ID（前端点击跳转）',
  read_flag  TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已读：0 未读 / 1 已读',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_user_read (user_id, read_flag, created_at)
) ENGINE = InnoDB COMMENT = '站内通知表';

-- ----------------------------------------------------------------------------
-- 9. meeting_recurring 周期会议规则（P1）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS meeting_recurring (
  id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '规则ID',
  organiser_id     BIGINT       NOT NULL                COMMENT '组织人ID',
  room_id          BIGINT       NOT NULL                COMMENT '会议室ID',
  title            VARCHAR(100) NOT NULL                COMMENT '会议主题',
  day_of_week      TINYINT      NOT NULL                COMMENT '每周第几天：1=周一 ... 7=周日',
  start_minutes    INT          NOT NULL                COMMENT '开始时间（当天分钟数，如 540=09:00）',
  duration_minutes INT          NOT NULL                COMMENT '时长（分钟）',
  start_date       DATE         NOT NULL                COMMENT '重复开始日期',
  end_date         DATE         NOT NULL                COMMENT '重复结束日期',
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_organiser (organiser_id)
) ENGINE = InnoDB COMMENT = '周期会议规则表';

-- ----------------------------------------------------------------------------
-- 10. meeting_status_log 会议状态变更日志
-- 对应 01 文档状态机「每次变更记录 who/when/from/to/event」
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS meeting_status_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  meeting_id  BIGINT       NOT NULL                COMMENT '会议ID',
  from_status TINYINT      DEFAULT NULL            COMMENT '变更前状态（创建时为 NULL）',
  to_status   TINYINT      NOT NULL                COMMENT '变更后状态',
  event       VARCHAR(30)  NOT NULL                COMMENT '事件：RESERVE/APPROVE/REJECT/CANCEL/START/END/EXTEND/MODIFY',
  operator_id BIGINT       DEFAULT NULL            COMMENT '操作人ID（系统任务为 NULL）',
  remark      VARCHAR(200) DEFAULT NULL            COMMENT '备注',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '变更时间',
  PRIMARY KEY (id),
  KEY idx_meeting (meeting_id)
) ENGINE = InnoDB COMMENT = '会议状态变更日志表';

-- ----------------------------------------------------------------------------
-- 16. file_object 文件对象元数据（会议纪要音频；预留会议材料/会议室图片）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS file_object (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  biz_type      VARCHAR(32)  NOT NULL COMMENT '业务类型：MINUTES_AUDIO',
  biz_id        BIGINT       NOT NULL COMMENT '业务主键：会议 id',
  object_key    VARCHAR(255) NOT NULL COMMENT '对象存储 key（服务端生成）',
  original_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
  size_bytes    BIGINT       NOT NULL COMMENT '文件字节数',
  content_type  VARCHAR(100) NOT NULL COMMENT 'MIME 类型',
  status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0 正常 / 10 已删除',
  uploaded_by   BIGINT       NOT NULL COMMENT '上传人 user_id',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_object_key (object_key),
  KEY idx_biz (biz_type, biz_id)
) ENGINE = InnoDB COMMENT = '文件对象元数据';

-- ----------------------------------------------------------------------------
-- 17. file_upload_session 分片上传会话
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS file_upload_session (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  upload_id     VARCHAR(128) NOT NULL COMMENT 'COS uploadId；本地 provider 为本地会话 id',
  biz_type      VARCHAR(32)  NOT NULL COMMENT '业务类型：MINUTES_AUDIO',
  biz_id        BIGINT       NOT NULL COMMENT '业务主键：会议 id',
  object_key    VARCHAR(255) NOT NULL COMMENT '目标对象 key',
  original_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
  size_bytes    BIGINT       NOT NULL COMMENT '声明文件大小',
  content_type  VARCHAR(100) NOT NULL COMMENT 'MIME 类型',
  chunk_size    INT          NOT NULL COMMENT '分片大小（字节）',
  total_parts   INT          NOT NULL COMMENT '分片总数',
  status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0 进行中 / 10 已完成 / 20 已中止 / 30 已过期',
  file_id       BIGINT       DEFAULT NULL COMMENT '完成后关联的 file_object.id',
  created_by    BIGINT       NOT NULL COMMENT '发起人 user_id',
  expires_at    DATETIME     NOT NULL COMMENT '会话过期时间',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_upload_id (upload_id),
  KEY idx_creator_status (created_by, status),
  KEY idx_status_expires (status, expires_at)
) ENGINE = InnoDB COMMENT = '分片上传会话';

-- ----------------------------------------------------------------------------
-- 18. meeting_minutes 会议纪要（一场会议一份）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS meeting_minutes (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  meeting_id        BIGINT       NOT NULL COMMENT '会议ID',
  room_id           BIGINT       NOT NULL COMMENT '会议室ID（冗余，便于排障）',
  audio_file_id     BIGINT       DEFAULT NULL COMMENT 'file_object.id',
  transcript_source VARCHAR(32)  NOT NULL DEFAULT 'TENCENT_ASR' COMMENT '转写来源',
  duration_ms       BIGINT       NOT NULL DEFAULT 0 COMMENT '录音时长（毫秒）',
  ai_status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0 未生成 / 10 待生成 / 20 生成中 / 30 成功 / 40 失败',
  summary_json      JSON         DEFAULT NULL COMMENT '结构化纪要',
  ai_model          VARCHAR(64)  DEFAULT NULL COMMENT '生成模型',
  ai_error          VARCHAR(500) DEFAULT NULL COMMENT '用户可读失败原因',
  ai_attempts       INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数',
  ai_started_at     DATETIME     DEFAULT NULL COMMENT '本次开始时间',
  ai_finished_at    DATETIME     DEFAULT NULL COMMENT '本次结束时间',
  asr_status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0 未转写 / 10 待识别 / 20 识别中 / 30 成功 / 40 失败',
  asr_task_id       VARCHAR(64)  DEFAULT NULL COMMENT '腾讯 ASR TaskId',
  asr_error         VARCHAR(500) DEFAULT NULL COMMENT '语音识别失败原因（用户可读）',
  asr_attempts      INT          NOT NULL DEFAULT 0 COMMENT '语音识别已尝试次数',
  asr_started_at    DATETIME     DEFAULT NULL COMMENT '本次提交 ASR 的时间（run token）',
  asr_finished_at   DATETIME     DEFAULT NULL COMMENT '本次识别结束时间',
  created_by        BIGINT       NOT NULL COMMENT '上传人',
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_meeting (meeting_id),
  KEY idx_ai_status (ai_status, updated_at),
  KEY idx_asr_status (asr_status, updated_at)
) ENGINE = InnoDB COMMENT = '会议纪要';

-- ----------------------------------------------------------------------------
-- 19. meeting_minutes_speaker 纪要发言人
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS meeting_minutes_speaker (
  id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  minutes_id   BIGINT      NOT NULL COMMENT '会议纪要ID',
  speaker_key  VARCHAR(32) NOT NULL COMMENT 'ASR 原始说话人编号',
  display_name VARCHAR(50) NOT NULL COMMENT '展示名，默认“说话人 N”',
  color        VARCHAR(16) NOT NULL COMMENT '展示色 #RRGGBB',
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_minutes_speaker (minutes_id, speaker_key)
) ENGINE = InnoDB COMMENT = '纪要发言人';

-- ----------------------------------------------------------------------------
-- 20. meeting_transcript_segment 转写分段
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS meeting_transcript_segment (
  id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  minutes_id  BIGINT      NOT NULL COMMENT '会议纪要ID',
  seq         INT         NOT NULL COMMENT '从 0 开始的顺序号',
  speaker_key VARCHAR(32) NOT NULL COMMENT 'ASR 原始说话人编号',
  start_ms    BIGINT      NOT NULL COMMENT '相对音频开始的毫秒偏移',
  end_ms      BIGINT      NOT NULL COMMENT '结束毫秒偏移',
  text        TEXT        NOT NULL COMMENT '分段文本',
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_minutes_seq (minutes_id, seq),
  KEY idx_minutes_start (minutes_id, start_ms)
) ENGINE = InnoDB COMMENT = '转写分段';

-- ----------------------------------------------------------------------------
-- 21. 应用账号（最小权限：业务服务不使用 root）
-- ⚠️ 这是开发默认口令；deploy/.env 可通过 DB_USER/DB_PASSWORD 覆盖，
--    compose 的 mysql-init 会在每次启动时同步密码。
-- ----------------------------------------------------------------------------
CREATE USER IF NOT EXISTS 'smt_app'@'%' IDENTIFIED BY 'smt_app123';
GRANT SELECT, INSERT, UPDATE, DELETE ON smt_meeting.* TO 'smt_app'@'%';
FLUSH PRIVILEGES;
