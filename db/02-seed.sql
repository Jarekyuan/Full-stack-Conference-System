-- ============================================================================
-- 会议室预约系统 · 初始化数据（种子数据）
-- 前置：先执行 01-schema.sql
-- 说明：仅供首次初始化使用；重复执行会因主键冲突报错。
--       如需重置：DROP DATABASE smt_meeting; 后重跑 01-schema.sql + 本文件。
--
-- 演示账号（登录密码）：
--   admin                / admin123  （管理员，兼审批人——演示多角色）
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
-- 1. 部门（树形结构；仅用于组织与筛选用户，不参与权限判定）
-- ----------------------------------------------------------------------------
INSERT INTO sys_dept (id, parent_id, name, sort_order, status) VALUES
(1, 0, '总公司', 1, 1),
(2, 1, '研发部', 2, 1),
(3, 1, '产品部', 3, 1),
(4, 1, '行政部', 4, 1);

-- ----------------------------------------------------------------------------
-- 2. 角色与权限（RBAC：字典表 + 角色权限关联；当前鉴权按角色，权限表作资源清单）
-- ----------------------------------------------------------------------------
INSERT INTO sys_role (id, code, name, description, status) VALUES
(1, 'USER',     '普通用户', '浏览会议室、创建与管理自己的预约', 1),
(2, 'APPROVER', '审批人',   '普通用户权限 + 审批操作（通过/拒绝）', 1),
(3, 'ADMIN',    '管理员',   '全部权限 + 会议室/用户/部门管理', 1);

INSERT INTO sys_permission (id, code, name, type, sort_order) VALUES
(1, 'room:view',       '会议室查看', 1, 1),
(2, 'meeting:create',  '创建预约',   1, 2),
(3, 'meeting:mine',    '我的会议',   1, 3),
(4, 'notification',    '通知中心',   1, 4),
(5, 'approval:handle', '审批中心',   1, 5),
(6, 'room:manage',     '会议室管理', 1, 6),
(7, 'user:manage',     '用户管理',   1, 7),
(8, 'dept:manage',     '部门管理',   1, 8),
(9, 'stats:view',      '统计看板',   1, 9);

-- 角色权限关联：USER 为基础集；APPROVER = USER + 审批；ADMIN = 全部
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(1, 1), (1, 2), (1, 3), (1, 4),
(2, 1), (2, 2), (2, 3), (2, 4), (2, 5),
(3, 1), (3, 2), (3, 3), (3, 4), (3, 5), (3, 6), (3, 7), (3, 8), (3, 9);

-- ----------------------------------------------------------------------------
-- 3. 用户（密码为 BCrypt 哈希，明文见文件头注释；角色改由 sys_user_role 关联）
-- ----------------------------------------------------------------------------
INSERT INTO sys_user (id, username, password, real_name, email, dept_id, status) VALUES
(1, 'admin',     '$2b$10$vjiRWldfRjk69u1IXSL76eyD09xw0tjr43IdONZi9MJFxrEop/8CC', '系统管理员',   'admin@example.com',     4, 1),
(2, 'approver1', '$2b$10$VG0BiGSyiATpYatSOFvH2umXf593WZRw.R1yZfKU3E1tyGk9TP86S', '审批人·王经理', 'approver1@example.com', 3, 1),
(3, 'approver2', '$2b$10$VG0BiGSyiATpYatSOFvH2umXf593WZRw.R1yZfKU3E1tyGk9TP86S', '审批人·李总监', 'approver2@example.com', 2, 1),
(4, 'user1',     '$2b$10$VG0BiGSyiATpYatSOFvH2umXf593WZRw.R1yZfKU3E1tyGk9TP86S', '张三',         'user1@example.com',     2, 1),
(5, 'user2',     '$2b$10$VG0BiGSyiATpYatSOFvH2umXf593WZRw.R1yZfKU3E1tyGk9TP86S', '李四',         'user2@example.com',     2, 1),
(6, 'user3',     '$2b$10$VG0BiGSyiATpYatSOFvH2umXf593WZRw.R1yZfKU3E1tyGk9TP86S', '王五',         'user3@example.com',     3, 1);

-- ----------------------------------------------------------------------------
-- 4. 用户角色关联（admin 兼 ADMIN + APPROVER，演示多角色）
-- ----------------------------------------------------------------------------
INSERT INTO sys_user_role (user_id, role_id) VALUES
(1, 3), -- admin     → ADMIN
(1, 2), -- admin     → APPROVER（同一人多角色）
(2, 2), -- approver1 → APPROVER
(3, 2), -- approver2 → APPROVER
(4, 1), -- user1     → USER
(5, 1), -- user2     → USER
(6, 1); -- user3     → USER

-- ----------------------------------------------------------------------------
-- 5. 会议室分组
-- ----------------------------------------------------------------------------
INSERT INTO room_group (id, name, sort_order) VALUES
(1, '3F 东区', 1),
(2, '3F 西区', 2),
(3, '4F 区',   3);

-- ----------------------------------------------------------------------------
-- 6. 会议室（含 2 个需审批的会议室：梧桐厅 / 红枫厅）
-- ----------------------------------------------------------------------------
INSERT INTO meeting_room (id, name, group_id, capacity, location, images, device_tags, status) VALUES
(1, '青竹厅', 1, 12, '3F-301', NULL, '["投影仪","白板"]',           0),
(2, '白桦厅', 1, 8,  '3F-302', NULL, '["白板"]',                    0),
(3, '梧桐厅', 2, 20, '3F-305', NULL, '["投影仪","视频会议","白板"]', 0),
(4, '银杏厅', 2, 6,  '3F-306', NULL, '["白板"]',                    0),
(5, '红枫厅', 3, 30, '4F-401', NULL, '["投影仪","视频会议","扩音"]', 0),
(6, '云杉厅', 3, 4,  '4F-402', NULL, '["电话亭"]',                  0);

-- ----------------------------------------------------------------------------
-- 7. 预约策略
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
