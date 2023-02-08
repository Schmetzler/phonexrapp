/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <android/log.h>
#include <jni.h>

#include <memory>

#include "phonexr_passthrough.h"

#define JNI_METHOD(return_type, method_name) \
  JNIEXPORT return_type JNICALL              \
      Java_de_lifecapture_phonexrapp_VrActivity_##method_name

namespace {

    inline jlong jptr(ndk_phonexr::PhoneXRPassthrough* native_app) {
        return reinterpret_cast<intptr_t>(native_app);
    }

    inline ndk_phonexr::PhoneXRPassthrough* native(jlong ptr) {
        return reinterpret_cast<ndk_phonexr::PhoneXRPassthrough*>(ptr);
    }

    JavaVM* javaVm;

}  // anonymous namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
javaVm = vm;
return JNI_VERSION_1_6;
}

JNI_METHOD(jlong, nativeOnCreate)
(JNIEnv* /*env*/, jobject obj) {
    return jptr(new ndk_phonexr::PhoneXRPassthrough(javaVm, obj));
}

JNI_METHOD(jint, nativeOnSurfaceCreated)
(JNIEnv* , jobject /*obj*/, jlong native_app) {
    GLuint res = native(native_app)->OnSurfaceCreated();
    return res;
}

JNI_METHOD(void, nativeOnDestroy)
(JNIEnv* /*env*/, jobject /*obj*/, jlong native_app) {
delete native(native_app);
}

JNI_METHOD(void, nativeOnDrawFrame)
(JNIEnv*, jobject /*obj*/, jlong native_app) {
native(native_app)->OnDrawFrame();
}

JNI_METHOD(void, nativeOnPause)
(JNIEnv* /*env*/, jobject /*obj*/, jlong native_app) {
native(native_app)->OnPause();
}

JNI_METHOD(void, nativeOnResume)
(JNIEnv* /*env*/, jobject /*obj*/, jlong native_app) {
native(native_app)->OnResume();
}

JNI_METHOD(void, nativeSetScreenParams)
(JNIEnv* /*env*/, jobject /*obj*/, jlong native_app, jint width, jint height) {
native(native_app)->SetScreenParams(width, height);
}

JNI_METHOD(void, nativeSetRenderViewport)
(JNIEnv* /*env*/, jobject /*obj*/, jlong native_app, jint x, jint y, jint width, jint height) {
    native(native_app)->SetRenderViewport(x, y, width, height);
}

JNI_METHOD(void, nativeSetPassthroughSize)
(JNIEnv* /*env*/, jobject /*obj*/, jlong native_app, jfloat passthrough_size) {
    native(native_app)->SetPassthroughSize(passthrough_size);
}

JNI_METHOD(void, nativeSwitchViewer)
(JNIEnv* /*env*/, jobject /*obj*/, jlong native_app) {
native(native_app)->SwitchViewer();
}

JNI_METHOD(jfloatArray, nativeGetHeadPose)
(JNIEnv* env, jobject /*obj*/, jlong native_app) {
    jfloatArray result = env->NewFloatArray(16);
    jfloat* pose = native(native_app)->GetPose().ToGlArray().data();
    env->SetFloatArrayRegion(result,0,16,pose);
    return result;
}

}  // extern "C"
