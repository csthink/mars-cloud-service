#!/usr/bin/env bash
#
# 公开仓安全性检查（通用版）
#
# 覆盖与内网无关、但一旦进公开仓就不可挽回的三类：凭据与私钥、本机绝对路径、
# 内网地址段（RFC1918）。
#
# 用法：
#   tools/check-public-safety-generic.sh [扫描目录，默认 .] [要检查的文件…]
#
#   不给文件参数 → 扫描整个仓中 **git 已跟踪** 的内容（CI 用这条路径）
#   给了文件参数 → 只检查这些文件（pre-commit hook 用这条路径，只查本次暂存的内容）
#
# 退出码：0 = 通过；1 = 发现违规；2 = 用法错误
#
# 为什么不能只扫文件系统：见文件末尾「设计约束」。
#
# 本脚本只包含可以公开的通用模式，是公开仓 hook 与 CI 的**唯一**实现。
# 与具体部署环境相关的模式不写进公开仓：模式本身就会暴露它要拦截的内容。

set -uo pipefail

# ── 供其它脚本 source 的复用接口 ───────────────────────────────
# 设置 SCANNER_NO_MAIN=1 再 source 本文件，即可只取下面这些函数、不执行主流程。
# 本仓的 pre-commit hook 与 CI 都直接执行本文件；需要复用同一份规则的脚本走这个接口，
# 避免「多处各写一遍、改一处漏一处」。

scanner_violations=0

scanner_report() {
  local category="$1" file="$2" line="$3" text="$4"
  printf '\033[31m[%s]\033[0m %s:%s\n        %s\n' \
    "$category" "$file" "$line" "$(printf '%s' "$text" | cut -c1-160)"
  scanner_violations=$((scanner_violations + 1))
}

# 通用规则：与内网无关的三类。
# $1 = 扫描目标（目录，或文件所在的 git 仓）
# $2..$n = 可选的具体文件列表；为空时扫描整个仓已跟踪的内容
scanner_run_generic_rules() {
  local target="$1"; shift
  local -a files=("$@")
  local -a grep_targets=()

  if [ "${#files[@]}" -gt 0 ]; then
    # 统一成**仓内相对路径**：调用方（pre-commit hook）传的是绝对路径，
    # 而 `git grep` 的输出是相对路径——不归一化的话，同一份违规在两处会显示成两种路径。
    # 用参数展开而不是 realpath：macOS 自带 bash 3.2 且没有 GNU realpath。
    local root rel
    root=$(git -C "$target" rev-parse --show-toplevel 2>/dev/null || echo "")
    local -a normalized=()
    if [ -n "$root" ]; then
      for f in "${files[@]}"; do
        rel="${f#"$root"/}"
        # 仓外的文件（没能剥掉前缀）不归本扫描器管
        if [ "$rel" = "$f" ] && [ "${f:0:1}" = "/" ]; then
          continue
        fi
        normalized+=("$rel")
      done
      files=("${normalized[@]}")
    fi
  fi

  if [ "${#files[@]}" -gt 0 ]; then
    grep_targets=(-- "${files[@]}")
  elif git -C "$target" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    # `git grep` 只搜**已跟踪**的文件：
    #   被忽略的文件不会进仓 → 报出来是误报（.vscode/settings.json 就踩过）
    #   已跟踪的文件一定被搜到 → 不依赖 --exclude-dir 那种会漏检的写法
    grep_targets=()
  else
    grep_targets=(-r --exclude-dir=target --exclude-dir=.git --exclude-dir=node_modules
                  --exclude-dir=build -- "$target")
  fi

  scanner_pattern() {
    local category="$1" pattern="$2"
    local matches
    # pattern 走 `-e`：含 / | * 的 pattern 若放进会被词拆的变量，会被 shell
    # 当路径做 glob 展开，结果是 grep 什么都没扫到、退出码仍为 0 的**静默失效**。
    if [ "${#files[@]}" -gt 0 ]; then
      # 加上 -H：单文件时 grep 默认不打印文件名，报错信息里就没有位置线索
      matches=$(grep -HnIE --binary-files=without-match -e "$pattern" "${grep_targets[@]}" 2>/dev/null || true)
    elif [ "${#grep_targets[@]}" -eq 0 ]; then
      matches=$(git -C "$target" grep -nIE --no-color -e "$pattern" 2>/dev/null || true)
    else
      matches=$(grep -rnIE --binary-files=without-match -e "$pattern" "${grep_targets[@]}" 2>/dev/null || true)
    fi
    while IFS=: read -r file line text; do
      [ -z "${file:-}" ] && continue
      scanner_report "$category" "$file" "$line" "$text"
    done <<< "$matches"
  }

  # 1. 凭据与私钥
  scanner_pattern "凭据" '(password|passwd|secret|token|apikey|api_key)[[:space:]]*[:=][[:space:]]*["'"'"'][^"'"'"']{8,}'
  scanner_pattern "私钥" 'BEGIN [A-Z ]*PRIVATE KEY'
  scanner_pattern "云凭据" 'AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{36}|glpat-[A-Za-z0-9_-]{20,}'

  # 2. 本机绝对路径与家目录引用
  scanner_pattern "本机路径" '/Users/[A-Za-z0-9._-]+/|/home/[A-Za-z0-9._-]+/'
  # 方括号里的 ~ 是字面字符：要找的是文件里写出来的 ~/ 路径，不是让 shell 展开家目录。
  scanner_pattern "家目录" '[~]/\.m2|[~]/\.ssh'

  # 3. 内网地址段（RFC1918）
  scanner_pattern "内网地址" 'https?://(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)'
}

# 违规时的处理指引（两个入口共用）
scanner_print_remedy() {
  cat <<'EOF'
❌ 未通过

处理方式：
  · 本机绝对路径 / 家目录引用 → 改成占位符或环境变量
  · 凭据 → **立即吊销该凭据**，然后改写历史；只删文件不够

注意：内容一旦已经 commit，仅删除文件没用 —— 它会留在 Git 历史里。
需要改写历史（git filter-repo）或轮换凭据。
EOF
}

# ── 设计约束（改这个脚本前必读）─────────────────────────────
# 1. 只扫 git 已跟踪的内容，判据是跟踪状态而不是文件系统遍历：
#    - 被忽略的文件不会进仓，报出来是假阳性。实例：编辑器自动生成的
#      .vscode/settings.json 里写着本机 JDK 路径，而 .vscode/ 本就在 .gitignore 里。
#      2026-09-19 实测：两个公开仓各因此被误报 5 处。
#    - 反之，靠 --exclude-dir 排除目录会漏掉**已跟踪**的同类文件——若有人先提交了
#      .vscode/settings.json 之后才加 .gitignore，排除目录就等于放过真正的问题。
# 2. pattern 一律走 -e，绝不放进会被词拆的变量（含 / | * 会被当路径做 glob 展开）。
# 3. **改动本脚本必须做阳性对照**：只验证「真实仓通过」无法区分「没有违规」与
#    「脚本坏了」——两种情况的输出都是「✅ 通过」。见 docs/ci.md。

# ── 主流程（被 source 时跳过）────────────────────────────────
if [ "${SCANNER_NO_MAIN:-0}" = "1" ]; then
  # 被 source 时回到调用方，只留下上面的函数；被直接执行时没有调用方可回，直接退出。
  (return 0 2>/dev/null) && return 0
  exit 0
fi

TARGET="${1:-.}"
shift 2>/dev/null || true
FILES=("$@")

if [ ! -d "$TARGET" ]; then
  echo "用法错误：目录不存在：$TARGET" >&2
  exit 2
fi

echo "==> 公开仓安全性检查（通用版）：$TARGET${FILES:+ （仅 ${#FILES[@]} 个指定文件）}"
echo

# 注意用 "${FILES[@]}"：写成 "${FILES[@]:-}" 在数组为空时会**展开成一个空字符串**，
# 导致多传一个空参数（曾被 `-- "${files[@]}"` 误判成「指定了 1 个文件」）。
scanner_run_generic_rules "$TARGET" "${FILES[@]}"

echo
if [ "$scanner_violations" -eq 0 ]; then
  echo "✅ 通过：未发现违规内容"
  exit 0
fi

scanner_print_remedy
exit 1
