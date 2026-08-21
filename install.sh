#!/bin/bash
set -e

adb -s 192.168.53.178:5555 install -r /Users/Garan/AndroidStudioProjects/TesNav/app/build/outputs/apk/debug/app-debug.apk
