# Android 本地构建环境
# 按自己机器改这几个路径，也可以用同名环境变量覆盖

export ANDROID_JAR=${ANDROID_JAR:-$HOME/android-sdk/platforms/android-34/android.jar}
export ANDROID_D8=${ANDROID_D8:-$HOME/android-sdk/build-tools/34.0.0/lib/d8.jar}
export ANDROID_AAPT2=${ANDROID_AAPT2:-aapt2}
export ANDROID_ZIPALIGN=${ANDROID_ZIPALIGN:-zipalign}
export ANDROID_APKSIGNER=${ANDROID_APKSIGNER:-apksigner}

# 签名密钥库：自己生成一个，或指向已有的
export ANDROID_KS=${ANDROID_KS:-$HOME/debug.keystore}

export ANDROID_MIN_SDK=21
export ANDROID_TARGET_SDK=34
