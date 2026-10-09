#!/bin/bash
# 在 Node 上跑 App 里的「协议自检」：core/ 下的 .ets 拷成 .ts，@kit 导入换成用 node:crypto 模拟的 shim。
# 只验证协议逻辑和向量；系统 cryptoFramework 本身的行为要在真机上点「协议自检」确认。需要 Node 22.6+。
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=$(mktemp -d)
for f in Proto Crypto SelfTest; do
  sed -e "s#'@kit.ArkTS'#'./shim_arkts.ts'#; s#'@kit.CryptoArchitectureKit'#'./shim_crypto.ts'#" \
      -e "s#from './\([A-Za-z]*\)'#from './\1.ts'#" "$here/../entry/src/main/ets/core/$f.ets" > "$out/$f.ts"
done
cp "$here"/shim_*.ts "$here/run.ts" "$out/"
node --experimental-strip-types --no-warnings "$out/run.ts" "$here/../../docs/testvectors/ios-pairing.json"
