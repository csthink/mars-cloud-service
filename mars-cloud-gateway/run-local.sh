#!/usr/bin/env bash
#
# 本地启动网关。
#
# 默认 local profile：需要本机 Nacos（注册发现 + 两层动态配置）。
# 路由目标（UPMS）不必先起来：没有实例时网关会返回信封式 503，UPMS 起来后自动可达。
#
# 环境相关的取值（地址、Namespace、账号）走环境变量，不写进配置文件：
#   - 若本目录存在 `.env`，本脚本会显式加载它（Maven 的 spring-boot:run 也会自行读取）
#   - 模板见仓根 `.env.example`；`.env` 不进版本库
#
# 本脚本不传 JVM 参数：nacos-client 在 JDK 24+ 需要的 --sun-misc-unsafe-memory-access=allow
# 由框架 BOM 统一给 spring-boot:run 配置（见 ../docs/deployment.md 的「JVM 参数」一节）。
#
set -euo pipefail

cd "$(dirname "$0")"

if [ ! -f .env ] && [ -z "${NACOS_NAMESPACE_ID:-}" ]; then
  echo "错误：未找到 .env，且当前环境没有 NACOS_NAMESPACE_ID。" >&2
  echo "      先复制 ../.env.example 为 .env，并填写本地 Nacos 参数。" >&2
  exit 1
fi

if [ -f .env ]; then
  echo "加载 .env"
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"

if [ -z "${NACOS_NAMESPACE_ID:-}" ] || [ -z "${NACOS_USERNAME:-}" ] || [ -z "${NACOS_PASSWORD:-}" ]; then
  echo "错误：NACOS_NAMESPACE_ID、NACOS_USERNAME 与 NACOS_PASSWORD 都不能为空。" >&2
  exit 1
fi

exec mvn spring-boot:run
