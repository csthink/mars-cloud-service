#!/usr/bin/env bash
#
# 本地启动 sample-service（框架使用示例）。
#
# 默认 local profile：本模块经 Nacos 服务发现调用 UPMS，启动前需要可用的 Nacos Namespace。
#
# 环境相关的取值（地址、端口）走环境变量，不写进配置文件：
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
