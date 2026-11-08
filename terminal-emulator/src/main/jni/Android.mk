LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libmolina_pty
LOCAL_SRC_FILES:= molina_pty.c
include $(BUILD_SHARED_LIBRARY)
