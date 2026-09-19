#!/usr/bin/env bash
#
# 本地启动 upms-service。
#
# 默认 local profile：内存快照 + 不装配数据源/Redis，无需任何外部依赖即可启动。
#
# 环境相关的取值（地址、端口、库名、口令）走环境变量，不写进配置文件：
#   - 若本目录存在 `.env`，本脚本会显式加载它（Maven 的 spring-boot:run 也会自行读取）
#   - 模板见仓根 `.env.example`；`.env` 不进版本库
#
# 显式加载而不是只依赖 Maven 的隐式行为，是为了让「值从哪来」一目了然。
#
set -euo pipefail

cd "$(dirname "$0")"

if [ ! -f .env ] && [ -f ../../.env.example ]; then
  echo "提示：未找到 .env，本次使用默认值（local profile 不需要任何连接参数）。"
  echo "      需要覆盖时：cp ../../.env.example .env 然后按需填写。"
fi

if [ -f .env ]; then
  echo "加载 .env"
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"

exec mvn spring-boot:run
