#!/usr/bin/env bash
#
# 公开仓安全性检查（通用版）
#
# 为什么是「通用版」：完整的检查清单里含有内部主机名等**本身就不能公开**的模式，
# 把那份脚本放进公开仓等于泄漏。本脚本只覆盖与内网无关、但同样致命的三类：
#
#   1. 凭据与私钥
#   2. 本机绝对路径与家目录引用（泄露开发者身份与目录结构）
#   3. 内网地址段（RFC1918）
#
# 用法（在仓根目录执行，或在 CI 里调用）：
#   tools/check-public-safety-generic.sh [扫描目录，默认 .]
#
# 退出码：0 = 通过；1 = 发现违规；2 = 用法错误
#
# 注意：pre-commit hook 可被 `git commit --no-verify` 绕过，**CI 是最后一道**，
# 所以这条检查在 CI 里必须跑，不能只依赖本地 hook。

set -uo pipefail

TARGET="${1:-.}"

if [ ! -d "$TARGET" ]; then
  echo "用法错误：目录不存在：$TARGET" >&2
  exit 2
fi

violations=0

report() {
  local category="$1" file="$2" line="$3" text="$4"
  printf '\033[31m[%s]\033[0m %s:%s\n        %s\n' \
    "$category" "$file" "$line" "$(printf '%s' "$text" | cut -c1-160)"
  violations=$((violations + 1))
}

scan() {
  local category="$1" pattern="$2"
  while IFS=: read -r file line text; do
    [ -z "${file:-}" ] && continue
    report "$category" "$file" "$line" "$text"
  done < <(grep -rnIE --binary-files=without-match "$pattern" "$TARGET" \
             --exclude-dir=target --exclude-dir=.git --exclude-dir=node_modules \
             --exclude-dir=.idea --exclude-dir=build 2>/dev/null || true)
}

echo "==> 公开仓安全性检查（通用版）：$TARGET"
echo

# 1. 凭据与私钥
scan "凭据" '(password|passwd|secret|token|apikey|api_key)[[:space:]]*[:=][[:space:]]*["'"'"'][^"'"'"']{8,}'
scan "私钥" 'BEGIN [A-Z ]*PRIVATE KEY'
scan "云凭据" 'AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{36}|glpat-[A-Za-z0-9_-]{20,}'

# 2. 本机绝对路径与家目录引用
scan "本机路径" '/Users/[A-Za-z0-9._-]+/|/home/[A-Za-z0-9._-]+/'
scan "家目录" '~/\.m2|~/\.ssh'

# 3. 内网地址段（RFC1918）
scan "内网地址" 'https?://(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)'

if [ "$violations" -gt 0 ]; then
  echo
  echo "❌ 发现 $violations 处违规：公开仓不得包含凭据、私钥、本机路径或内网地址。" >&2
  exit 1
fi

echo "✅ 通过：未发现违规内容"
exit 0
