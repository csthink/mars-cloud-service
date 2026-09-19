#!/usr/bin/env bash
#
# 本地启动 upms-service（local profile：内存快照 + 不装配外部数据源/Redis）。
#
# 需要连接外部基础设施时，自行导出环境变量覆盖：
#   SPRING_PROFILES_ACTIVE / SPRING_DATASOURCE_URL / MYSQL_USERNAME / MYSQL_PASSWORD
#   SPRING_DATA_REDIS_HOST / SPRING_DATA_REDIS_PORT / SPRING_DATA_REDIS_PASSWORD
#
set -euo pipefail

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"

exec mvn spring-boot:run
