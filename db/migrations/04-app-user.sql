-- 已有库补最小权限应用账号：业务服务不再用 root 连接
-- 新库由 db/01-schema.sql 自动创建；执行后请同步 deploy/.env 的 DB_USER/DB_PASSWORD。
CREATE USER IF NOT EXISTS 'smt_app'@'%' IDENTIFIED BY 'smt_app123';
ALTER USER 'smt_app'@'%' IDENTIFIED BY 'smt_app123';
GRANT SELECT, INSERT, UPDATE, DELETE ON smt_meeting.* TO 'smt_app'@'%';
FLUSH PRIVILEGES;
