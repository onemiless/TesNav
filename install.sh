#!/bin/bash
set -e

cd /Users/Garan/AndroidStudioProjects/TesNav
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
adb -s 192.168.53.178:5555 install -r /Users/Garan/AndroidStudioProjects/TesNav/app/build/outputs/apk/debug/app-debug.apk
