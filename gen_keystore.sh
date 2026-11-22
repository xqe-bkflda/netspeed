#!/usr/bin/env bash
# 生成本地调试签名密钥
# 用法: ./gen_keystore.sh [输出路径]
set -e
OUT=${1:-./debug-signing}
if [ -f "$OUT" ]; then echo "已存在: $OUT"; exit 0; fi
keytool -genkeypair -v -keystore "$OUT" -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
echo "已生成: $OUT"
