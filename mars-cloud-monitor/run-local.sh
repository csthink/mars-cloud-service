#!/usr/bin/env bash
#
# 本地启动监控面板。
#
# 默认 local profile：经注册中心发现被监控的实例，启动前需要可用的 Namespace。
#
# 环境相关的取值（地址、端口、账号）走环境变量，不写进配置文件：
#   - 若本目录存在 `.env`，本脚本会显式加载它
#   - 模板见仓根 `.env.example`；`.env` 不进版本库
#
set -euo pipefail

cd "$(dirname "$0")"

if [ -f .env ]; then
  echo "加载 .env"
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"

exec mvn spring-boot:run
