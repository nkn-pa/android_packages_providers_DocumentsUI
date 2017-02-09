LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
LOCAL_SRC_FILES := $(call all-java-files-under, src) \
    $(call all-java-files-under, ../tests/common/com/android/documentsui) \
    ../tests/functional/com/android/documentsui/ActivityTest.java

LOCAL_JAVA_LIBRARIES := android-support-v4 android.test.runner
LOCAL_STATIC_JAVA_LIBRARIES := \
    mockito-target \
    ub-uiautomator \
    ub-janktesthelper \
    espresso-core \
    legacy-android-test

LOCAL_PACKAGE_NAME := DocumentsUIPerfTests
LOCAL_INSTRUMENTATION_FOR := DocumentsUI

LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)

