#!/usr/bin/env bash
#
# 安装本仓的 pre-commit hook。
#
# 新克隆一个仓之后跑一次即可（幂等，可重复执行）。
#
#   ./tools/install-hooks.sh
#
# 为什么 hook 只是一行转发而不是把检查逻辑拷进去：
#   1. 检查逻辑**只有一份**（仓内 tools/check-public-safety-generic.sh），
#      改一处就生效，不会出现「本地 hook 与 CI 判据不一致」。
#   2. hook 位于 .git/ 下，**不在版本库里**——它无法被 review，也无法随仓分发，
#      所以它必须是「薄」的：真正的逻辑留在可 review、可版本化的仓内脚本里。
#
# 使用说明见 docs/ci.md。

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
HOOK="$(git rev-parse --git-path hooks/pre-commit)"
mkdir -p "$(dirname "$HOOK")"
SCANNER="$REPO_ROOT/tools/check-public-safety-generic.sh"

if [ ! -f "$SCANNER" ]; then
  echo "错误：找不到扫描器 $SCANNER" >&2
  exit 1
fi

cat > "$HOOK" <<'HOOK_CONTENT'
#!/usr/bin/env bash
#
# 由 tools/install-hooks.sh 生成 —— **请勿手工修改**。
# 检查逻辑在仓内的 tools/check-public-safety-generic.sh（改那里，别改这里）。
#
# 只检查本次**暂存**的内容，对应「提交前拦截」。
# 注意：本 hook 可被 `git commit --no-verify` 绕过，所以 CI 侧必须再检一次
# （两处用的是同一份扫描脚本，判据一致）。

set -uo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"

# -z 处理含空格与中文的文件名
STAGED=()
while IFS= read -r -d '' path; do
  STAGED+=("$path")
done < <(git diff --cached --name-only --diff-filter=ACM -z)

if [ "${#STAGED[@]}" -eq 0 ]; then
  exit 0
fi

cd "$REPO_ROOT" || exit 1

# 传归档路径而不是仓内相对路径：`git grep` 只认跟踪状态，
# 而暂存的新文件还没进 HEAD，必须按路径显式检查。
exec "$REPO_ROOT/tools/check-public-safety-generic.sh" "$REPO_ROOT" "${STAGED[@]}"
HOOK_CONTENT

chmod +x "$HOOK"

echo "已安装 pre-commit hook：$HOOK"
echo "检查逻辑：$SCANNER"
