-- ============================================================================
-- 会议室预约系统 · 初始化数据（种子数据）
-- 前置：先执行 01-schema.sql
-- 说明：仅供首次初始化使用；重复执行会因主键冲突报错。
--       如需重置：DROP DATABASE smt_meeting; 后重跑 01-schema.sql + 本文件。
--
-- 演示账号（登录密码）：
--   admin                / admin123  （管理员）
--   approver1, approver2 / user123   （审批人）
--   user1, user2, user3  / user123   （普通用户）
--
-- 说明：种子数据不预置任何"会议"——保持初始时段干净，
--       预约/冲突/审批演示通过接口或前端实时创建（原因见 03 文档第六章）。
-- ============================================================================

-- 强制本会话字符集为 utf8mb4（同 01-schema.sql，防止中文双重编码）
SET NAMES utf8mb4;

USE smt_meeting;

-- ----------------------------------------------------------------------------
-- 1. 用户（密码为 BCrypt 哈希，明文见文件头注释）
-- ----------------------------------------------------------------------------
INSERT INTO sys_user (id, username, password, real_name, email, role, status) VALUES
(1, 'admin',     '$2b$10$vjiRWldfRjk69u1IXSL76eyD09xw0tjr43IdONZi9MJFxrEop/8CC', '系统管理员',   'admin@example.com',     'ADMIN',    1),
(2, 'approver1', '$2b$10$VG0BiGSyiATpYatSOFvH2umXf593WZRw.R1yZfKU3E1tyGk9TP86S', '审批人·王经理', 'approver1@example.com', 'APPROVER', 1),
(3, 'approver2', '$2b$10$VG0BiGSyiATpYatSOFvH2umXf593WZRw.R1yZfKU3E1tyGk9TP86S', '审批人·李总监', 'approver2@example.com', 'APPROVER', 1),
(4, 'user1',     '$2b$10$VG0BiGSyiATpYatSOFvH2umXf593WZRw.R1yZfKU3E1tyGk9TP86S', '张三',         'user1@example.com',     'USER',     1),
(5, 'user2',     '$2b$10$VG0BiGSyiATpYatSOFvH2umXf593WZRw.R1yZfKU3E1tyGk9TP86S', '李四',         'user2@example.com',     'USER',     1),
(6, 'user3',     '$2b$10$VG0BiGSyiATpYatSOFvH2umXf593WZRw.R1yZfKU3E1tyGk9TP86S', '王五',         'user3@example.com',     'USER',     1);

-- ----------------------------------------------------------------------------
-- 2. 会议室分组
-- ----------------------------------------------------------------------------
INSERT INTO room_group (id, name, sort_order) VALUES
(1, '3F 东区', 1),
(2, '3F 西区', 2),
(3, '4F 区',   3);

-- ----------------------------------------------------------------------------
-- 3. 会议室（含 2 个需审批的会议室：梧桐厅 / 红枫厅）
-- ----------------------------------------------------------------------------
INSERT INTO meeting_room (id, name, group_id, capacity, location, images, device_tags, status) VALUES
(1, '青竹厅', 1, 12, '3F-301', NULL, '["投影仪","白板"]',           0),
(2, '白桦厅', 1, 8,  '3F-302', NULL, '["白板"]',                    0),
(3, '梧桐厅', 2, 20, '3F-305', NULL, '["投影仪","视频会议","白板"]', 0),
(4, '银杏厅', 2, 6,  '3F-306', NULL, '["白板"]',                    0),
(5, '红枫厅', 3, 30, '4F-401', NULL, '["投影仪","视频会议","扩音"]', 0),
(6, '云杉厅', 3, 4,  '4F-402', NULL, '["电话亭"]',                  0);

-- ----------------------------------------------------------------------------
-- 4. 预约策略
--    - 梧桐厅 / 红枫厅：需审批 + 30 分钟取消截止 + 15 分钟锁定缓冲（演示 R-13）
--    - 云杉厅（电话亭）：不允许周期会议
-- ----------------------------------------------------------------------------
INSERT INTO room_strategy (room_id, need_approval, advance_days, max_duration_minutes, cancel_deadline_minutes, buffer_minutes, allow_recurring) VALUES
(1, 0, 30, 240, 0,  0,  1),
(2, 0, 30, 240, 0,  0,  1),
(3, 1, 30, 240, 30, 15, 1),
(4, 0, 30, 120, 0,  0,  1),
(5, 1, 30, 480, 30, 15, 1),
(6, 0, 30, 120, 0,  0, 0);
