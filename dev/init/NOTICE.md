# 上游来源

`001-xxl-schema.sql` 根据 [XXL-JOB 3.4.2 官方 SQL](https://github.com/xuxueli/xxl-job/blob/3.4.2/doc/db/tables_xxl_job.sql) 修改，版权归 xuxueli，许可证见 `XXL-JOB-LICENSE`。

本副本仅保留表结构，建表加 `IF NOT EXISTS` 以允许初始化中断后重试。库名、账号、随机凭据和停止状态的验证任务由入口单独初始化；不包含上游示例用户或示例任务。不要修改已应用的版本文件，后续结构变更新增版本。

Jaeger 配置根据 [2.21.0 Badger 示例](https://github.com/jaegertracing/jaeger/blob/v2.21.0/cmd/jaeger/config-badger.yaml) 编写，遵循 Apache-2.0 许可证。健康探测程序来自固定 BusyBox 官方镜像，使用 GPL-2.0；二进制及相应源码由 [BusyBox 1.37.0](https://busybox.net/downloads/busybox-1.37.0.tar.bz2) 提供。
