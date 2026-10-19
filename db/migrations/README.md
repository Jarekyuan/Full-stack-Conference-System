# 数据库迁移脚本

> 这里的脚本**不会**被 MySQL 容器自动执行。`db/01-schema.sql` + `db/02-seed.sql`
> 只用于**全新数据卷**的首次初始化；已有库需要按需手动执行本目录下的迁移。

## 约定

- 文件名：`YYYY-MM-DD-<主题>.sql` 或沿用历史编号
- 每个脚本只执行一次；执行前先备份
- 新库不要执行本目录脚本：`01-schema.sql` 已包含最新结构

## 已有迁移

| 脚本 | 适用场景 | 说明 |
|------|----------|------|
| `03-asr-task.sql` | M6.7 之前创建的库 | 给 `meeting_minutes` 补 `asr_*` 字段与 `idx_asr_status` |
| `04-app-user.sql` | 已有库启用最小权限账号 | 创建 `smt_app`，仅授予 DML；同步 `DB_USER`/`DB_PASSWORD` |

执行示例：

```bash
mysql -h127.0.0.1 -P3306 -uroot -p smt_meeting < db/migrations/03-asr-task.sql
```
